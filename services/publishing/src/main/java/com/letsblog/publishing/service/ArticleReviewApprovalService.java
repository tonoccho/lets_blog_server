package com.letsblog.publishing.service;

import com.letsblog.publishing.client.ContentServiceClient;
import com.letsblog.publishing.client.ProjectServiceClient;
import com.letsblog.publishing.client.ProjectServiceClient.GithubAccess;
import com.letsblog.publishing.client.ProjectServiceClient.ProjectBridge;
import com.letsblog.publishing.client.ProjectServiceClient.SiteBridge;
import com.letsblog.publishing.domain.ArticleReview;
import com.letsblog.publishing.domain.ArticleReviewState;
import com.letsblog.publishing.dto.ArticleApproveResponse;
import com.letsblog.publishing.dto.PostPublishResponse;
import com.letsblog.publishing.dto.PullRequestArticleResponse;
import com.letsblog.publishing.dto.PullRequestArticleResponse.FrontMatter;
import com.letsblog.publishing.github.GithubApiException;
import com.letsblog.publishing.github.GithubPullRequestClient;
import com.letsblog.publishing.github.GithubPullRequestDetail;
import com.letsblog.publishing.github.PullRequestNotMergeableException;
import com.letsblog.publishing.repository.ArticleReviewRepository;
import com.letsblog.publishing.service.PullRequestArticleService.PublishableArticle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * レビュー完了: 記事を本番環境へ投稿し、Pull Requestをマージし、headブランチを削除する(issue #1343、Epic #1333)。
 *
 * <p>順序は「本番投稿 → マージ → ブランチ削除」で、逆にしない(マージしてから投稿に失敗すると、PRもheadも
 * 失われて記事を取り直せない)。投稿に成功した場合のみマージし、マージに成功した場合のみブランチを削除する。
 * 投稿の前に、PRがマージ可能か(コンフリクトしていない・{@code mergeable}がnullでない・未マージ)を確かめ、
 * 不可なら本番へ何も書かずに拒否する。強制マージはしない。
 *
 * <p>本番投稿は既存の拡張からの本番投稿と同じ規則に揃える(利用者決定 2026-09-17): front matterの
 * {@code status}(無ければ{@code draft})と{@code publish_scheduled_at}をそのまま{@link PostPublishService}へ渡す。
 * 同じスラッグの投稿が本番に既にあれば{@code wpPostId}を渡して更新にする。
 *
 * <p>状態の保存はマージに成功したあとの1回だけ。投稿後・マージ前に失敗した場合、本番の記事は残るが状態は
 * レビュー中のままで、同じ操作を再実行すれば(スラッグで引き当てて更新するので)重複せずやり直せる。
 * ブランチ削除の失敗は、公開・マージ済みの結果を覆さないため警告にとどめ、応答の{@code branchDeleted}で伝える。
 * リモートI/Oをトランザクションの外で行うため、このクラスは{@code @Transactional}にしない。
 */
@Service
public class ArticleReviewApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ArticleReviewApprovalService.class);

    /** front matterに{@code status}が無いときの本番の既定(拡張の本番投稿と同じ)。 */
    private static final String DEFAULT_PRODUCTION_STATUS = "draft";

    private final ProjectServiceClient projectServiceClient;
    private final PullRequestArticleService pullRequestArticleService;
    private final ContentServiceClient contentServiceClient;
    private final PostPublishService postPublishService;
    private final ArticleReviewRepository repository;
    private final GithubPullRequestClient githubClient;

    public ArticleReviewApprovalService(ProjectServiceClient projectServiceClient,
            PullRequestArticleService pullRequestArticleService, ContentServiceClient contentServiceClient,
            PostPublishService postPublishService, ArticleReviewRepository repository,
            GithubPullRequestClient githubClient) {
        this.projectServiceClient = projectServiceClient;
        this.pullRequestArticleService = pullRequestArticleService;
        this.contentServiceClient = contentServiceClient;
        this.postPublishService = postPublishService;
        this.repository = repository;
        this.githubClient = githubClient;
    }

    /**
     * @throws ArticleReviewNotFoundException そのPRが提出されていない(404)
     * @throws IllegalStateException レビュー中(IN_REVIEW)でない、または本番環境のサイトが紐づいていない(409)
     * @throws PullRequestNotMergeableException コンフリクト・マージ可否が未確定・マージ済み・マージの拒否(409)
     */
    public ArticleApproveResponse approve(Long projectId, Long actorId, GithubAccess access, int prNumber) {
        ArticleReview review = repository.findByProjectIdAndGithubPrNumber(projectId, prNumber)
                .orElseThrow(() -> new ArticleReviewNotFoundException(
                        "Pull Request #" + prNumber + " は提出されていません。先に記事を提出してください"));
        if (review.getState() != ArticleReviewState.IN_REVIEW) {
            throw new IllegalStateException("Pull Request #" + prNumber + " はレビュー中ではない(現在の状態: "
                    + review.getState() + ")ため完了できません。完了できるのはレビュー中の記事だけです");
        }
        SiteBridge productionSite = resolveProductionSite(projectId);

        GithubPullRequestDetail detail = githubClient.getPullRequest(access, prNumber);
        requireMergeable(prNumber, detail);

        PublishableArticle fetched = pullRequestArticleService.fetchForPublish(access, prNumber);
        PullRequestArticleResponse article = fetched.article();
        FrontMatter frontMatter = article.frontMatter();
        if (frontMatter.title() == null || frontMatter.title().isBlank()) {
            throw new PullRequestArticleException(PullRequestArticleException.Kind.INVALID,
                    "article.mdのfront matterにtitleがありません: 本番環境へ投稿できません");
        }
        String slug = frontMatter.slug() == null || frontMatter.slug().isBlank()
                ? article.slug() : frontMatter.slug();
        String status = frontMatter.status() == null || frontMatter.status().isBlank()
                ? DEFAULT_PRODUCTION_STATUS : frontMatter.status();

        String existingWpPostId = contentServiceClient.findPostBySlug(productionSite.siteKey(), slug)
                .map(ContentServiceClient.PostSlugLookup::wpPostId)
                .orElse(null);

        PostPublishResponse published = postPublishService.publish(ArticleReviewPublishService.command(
                productionSite.siteKey(), slug, existingWpPostId, article, fetched.assetBytes(), status,
                frontMatter.publishScheduledAt()));

        try {
            githubClient.mergePullRequest(access, prNumber);
        } catch (PullRequestNotMergeableException e) {
            throw new PullRequestNotMergeableException("記事は本番環境へ投稿済み(" + published.wpPostUrl()
                    + ")ですが、Pull Requestをマージできませんでした。原因を解消してから、もう一度レビューを完了してください: "
                    + e.getMessage());
        }

        review.markPublished(published.wpPostUrl(), actorId);
        repository.save(review);

        boolean branchDeleted = deleteBranch(access, detail.headRef(), prNumber);
        return new ArticleApproveResponse(
                prNumber, review.getState(), review.getProductionPostUrl(), published.wpPostId(), true, branchDeleted);
    }

    private static void requireMergeable(int prNumber, GithubPullRequestDetail detail) {
        if (detail.merged()) {
            throw new PullRequestNotMergeableException(
                    "Pull Request #" + prNumber + " は既にマージ済みのため、レビューを完了できません");
        }
        if (detail.mergeable() == null) {
            throw new PullRequestNotMergeableException("Pull Request #" + prNumber
                    + " のマージ可否をGitHubが計算中です。マージ可能と確認できないため本番投稿もマージも行いません。"
                    + "しばらくしてからもう一度レビューを完了してください");
        }
        if (!detail.mergeable()) {
            throw new PullRequestNotMergeableException("Pull Request #" + prNumber
                    + " はコンフリクトしているためマージできません。本番投稿もマージも行いません。"
                    + "執筆者が作業ブランチでコンフリクトを解決してpushし直してから、もう一度レビューを完了してください");
        }
    }

    private boolean deleteBranch(GithubAccess access, String headRef, int prNumber) {
        try {
            githubClient.deleteBranch(access, headRef);
            return true;
        } catch (GithubApiException e) {
            log.warn("Pull Request #{} のマージ後にheadブランチ{}を削除できませんでした: {}", prNumber, headRef, e.getMessage());
            return false;
        }
    }

    private SiteBridge resolveProductionSite(Long projectId) {
        ProjectBridge project = projectServiceClient.getProject(projectId);
        if (project.productionSiteId() == null) {
            throw new IllegalStateException(
                    "プロジェクトに本番環境のサイトが紐づいていません。先に本番環境のサイトを紐づけてください");
        }
        return projectServiceClient.getSite(project.productionSiteId())
                .orElseThrow(() -> new IllegalStateException(
                        "プロジェクトの本番環境のサイト(id " + project.productionSiteId() + ")が見つかりません。"
                                + "本番環境のサイトの紐づけを確認してください"));
    }
}
