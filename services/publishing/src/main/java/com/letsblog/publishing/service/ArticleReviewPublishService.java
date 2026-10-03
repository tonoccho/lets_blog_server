package com.letsblog.publishing.service;

import com.letsblog.publishing.client.ContentServiceClient;
import com.letsblog.publishing.client.ProjectServiceClient;
import com.letsblog.publishing.client.ProjectServiceClient.GithubAccess;
import com.letsblog.publishing.client.ProjectServiceClient.ProjectBridge;
import com.letsblog.publishing.client.ProjectServiceClient.SiteBridge;
import com.letsblog.publishing.domain.ArticleReview;
import com.letsblog.publishing.domain.ArticleReviewState;
import com.letsblog.publishing.dto.ArticleReviewResponse;
import com.letsblog.publishing.dto.PostPublishCommand;
import com.letsblog.publishing.dto.PostPublishResponse;
import com.letsblog.publishing.dto.PullRequestArticleResponse;
import com.letsblog.publishing.dto.PullRequestArticleResponse.FrontMatter;
import com.letsblog.publishing.repository.ArticleReviewRepository;
import com.letsblog.publishing.service.PullRequestArticleService.PublishableArticle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * PRの記事をテスト環境へ投稿し、レビュー中へ遷移させる(issue #1341、Epic #1333)。
 *
 * <p>記事はPRのheadから取り(#1338)、既存の{@link PostPublishService}でプロジェクトのテスト環境のサイトへ
 * 即公開(拡張のローカル/テストの{@code forceStatus: 'publish'}と同じ扱い)で投稿する。同じスラッグの投稿が
 * 既にあれば{@code wpPostId}を渡して更新にするので、再レビューで記事は重複しない。
 *
 * <p>投稿(リモートI/O)をトランザクションの外で行うため、このクラスは{@code @Transactional}にしない。
 * 状態の保存は投稿に成功したあとの{@link ArticleReviewRepository#save}1回だけなので、投稿が失敗すれば
 * 状態は進まない。それまでにアップロード済みの画像は巻き戻さず残す(利用者の判断 2026-10-01)。
 */
@Service
public class ArticleReviewPublishService {

    /** テスト環境は即公開する(拡張の{@code buildEnvironmentOptions()}がテストに与える{@code forceStatus}と同じ)。 */
    private static final String TEST_ENVIRONMENT_STATUS = "publish";

    private final ProjectServiceClient projectServiceClient;
    private final PullRequestArticleService pullRequestArticleService;
    private final ContentServiceClient contentServiceClient;
    private final PostPublishService postPublishService;
    private final ArticleReviewRepository repository;

    public ArticleReviewPublishService(ProjectServiceClient projectServiceClient,
            PullRequestArticleService pullRequestArticleService, ContentServiceClient contentServiceClient,
            PostPublishService postPublishService, ArticleReviewRepository repository) {
        this.projectServiceClient = projectServiceClient;
        this.pullRequestArticleService = pullRequestArticleService;
        this.contentServiceClient = contentServiceClient;
        this.postPublishService = postPublishService;
        this.repository = repository;
    }

    /**
     * @throws IllegalStateException テスト環境のサイトがプロジェクトに紐づいていない(409)
     * @throws IllegalStateException 既に公開済み(PUBLISHED)の行は、レビュー中へ戻さないため拒否する(409)
     * @throws ArticleReviewNotFoundException そのPRが提出されていない(404)
     */
    public ArticleReviewResponse review(Long projectId, Long actorId, GithubAccess access, int prNumber) {
        SiteBridge testSite = resolveTestSite(projectId);
        ArticleReview review = repository.findByProjectIdAndGithubPrNumber(projectId, prNumber)
                .orElseThrow(() -> new ArticleReviewNotFoundException(
                        "Pull Request #" + prNumber + " は提出されていません。先に記事を提出してください"));
        if (review.getState() == ArticleReviewState.PUBLISHED) {
            throw new IllegalStateException(
                    "Pull Request #" + prNumber + " の記事は公開済みのため、テスト環境へ再投稿してレビュー中へ戻せません");
        }

        PublishableArticle fetched = pullRequestArticleService.fetchForPublish(access, prNumber);
        PullRequestArticleResponse article = fetched.article();
        FrontMatter frontMatter = article.frontMatter();
        if (frontMatter.title() == null || frontMatter.title().isBlank()) {
            throw new PullRequestArticleException(PullRequestArticleException.Kind.INVALID,
                    "article.mdのfront matterにtitleがありません: テスト環境へ投稿できません");
        }
        String slug = frontMatter.slug() == null || frontMatter.slug().isBlank()
                ? article.slug() : frontMatter.slug();

        String existingWpPostId = contentServiceClient.findPostBySlug(testSite.siteKey(), slug)
                .map(ContentServiceClient.PostSlugLookup::wpPostId)
                .orElse(null);

        PostPublishResponse published = postPublishService.publish(
                command(testSite.siteKey(), slug, existingWpPostId, article, fetched.assetBytes(),
                        TEST_ENVIRONMENT_STATUS, null));

        review.markInReview(published.wpPostUrl(), actorId);
        repository.save(review);
        return new ArticleReviewResponse(
                prNumber, review.getState(), review.getTestPostUrl(), review.getReviewedByUserId(),
                published.wpPostId());
    }

    private SiteBridge resolveTestSite(Long projectId) {
        ProjectBridge project = projectServiceClient.getProject(projectId);
        if (project.testSiteId() == null) {
            throw new IllegalStateException(
                    "プロジェクトにテスト環境のサイトが紐づいていません。先にテスト環境のサイトを紐づけてください");
        }
        return projectServiceClient.getSite(project.testSiteId())
                .orElseThrow(() -> new IllegalStateException(
                        "プロジェクトのテスト環境のサイト(id " + project.testSiteId() + ")が見つかりません。"
                                + "テスト環境のサイトの紐づけを確認してください"));
    }

    /**
     * 投稿コマンドを組み立てる。{@code imageReferences}には、Markdown中の元の参照文字列
     * ({@code assets/xxx.png})を{@code images}と同じ順序で渡す(渡さないとパス区切りを含むfilenameの
     * 往復で壊れる。{@code PostController}のJavadoc参照)。
     */
    static PostPublishCommand command(String siteKey, String slug, String wpPostId,
            PullRequestArticleResponse article, Map<String, byte[]> assetBytes, String status,
            String publishScheduledAt) {
        List<MultipartFile> images = new ArrayList<>();
        List<String> imageReferences = new ArrayList<>();
        assetBytes.forEach((name, bytes) -> {
            String reference = "assets/" + name;
            images.add(new BytesMultipartFile("images", reference, contentTypeOf(name), bytes));
            imageReferences.add(reference);
        });
        FrontMatter frontMatter = article.frontMatter();
        return new PostPublishCommand(siteKey, frontMatter.title(), slug, status,
                frontMatter.categories(), frontMatter.tags(), wpPostId, article.body(), images,
                frontMatter.featuredImage(), imageReferences, publishScheduledAt);
    }

    private static String contentTypeOf(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }
}
