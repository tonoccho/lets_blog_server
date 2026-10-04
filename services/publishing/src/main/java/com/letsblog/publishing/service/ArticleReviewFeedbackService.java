package com.letsblog.publishing.service;

import com.letsblog.publishing.client.ProjectServiceClient;
import com.letsblog.publishing.client.ProjectServiceClient.GithubAccess;
import com.letsblog.publishing.domain.ArticleReview;
import com.letsblog.publishing.domain.ArticleReviewState;
import com.letsblog.publishing.dto.ArticleRejectResponse;
import com.letsblog.publishing.dto.MyArticleReviewResponse;
import com.letsblog.publishing.github.GithubIssueComment;
import com.letsblog.publishing.github.GithubPullRequestClient;
import com.letsblog.publishing.repository.ArticleReviewRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 記事の差し戻しと、自分宛のレビュー一覧(issue #1344、Epic #1333)。
 *
 * <p>差し戻しは指摘事項をPull Requestのコメントとして投稿し、{@code article_reviews}を差し戻しへ遷移させて
 * 実施者・時刻・コメントIDを記録する。コメントのGitHub上の投稿者はトークン所有者(プロジェクト共通トークンなら
 * 全員同じ)になるため、Let's Blog上の実施者はコメント本文に書き込む。指摘の本文は{@code article_reviews}に
 * 持たず、GitHub側にだけ置く(編集されたときに食い違わないように)。
 *
 * <p>GitHubへの呼び出し(リモートI/O)をトランザクションの外で行うため、このクラスは{@code @Transactional}に
 * しない。状態の保存はコメント投稿に成功したあとの{@link ArticleReviewRepository#save}1回だけなので、
 * 投稿が失敗すれば状態は進まない。
 */
@Service
public class ArticleReviewFeedbackService {

    private final ArticleReviewRepository repository;
    private final GithubPullRequestClient githubClient;
    private final ProjectServiceClient projectServiceClient;

    public ArticleReviewFeedbackService(ArticleReviewRepository repository, GithubPullRequestClient githubClient,
            ProjectServiceClient projectServiceClient) {
        this.repository = repository;
        this.githubClient = githubClient;
        this.projectServiceClient = projectServiceClient;
    }

    /**
     * @throws ArticleReviewNotFoundException そのPRが提出されていない(404)
     * @throws IllegalStateException レビュー中(IN_REVIEW)でない(409)。コメントも状態変更も行わない
     */
    public ArticleRejectResponse reject(
            Long projectId, Long actorId, String actorEmail, int prNumber, String comment) {
        ArticleReview review = repository.findByProjectIdAndGithubPrNumber(projectId, prNumber)
                .orElseThrow(() -> new ArticleReviewNotFoundException(
                        "Pull Request #" + prNumber + " は提出されていません。差し戻せるのは提出済みでレビュー中の記事です"));
        if (review.getState() != ArticleReviewState.IN_REVIEW) {
            throw new IllegalStateException("Pull Request #" + prNumber + " はレビュー中ではない(現在の状態: "
                    + review.getState() + ")ため差し戻せません。差し戻せるのはレビュー中の記事だけです");
        }
        GithubAccess access = projectServiceClient.resolveGithubAccess(projectId, actorId);
        GithubIssueComment posted = githubClient.createIssueComment(
                access, prNumber, commentBody(actorId, actorEmail, comment));

        review.markChangesRequested(actorId, posted.id());
        repository.save(review);
        return ArticleRejectResponse.of(
                prNumber, review.getState(), posted.id(), actorId, review.getRejectedAt());
    }

    /**
     * 呼び出し元が提出した記事の一覧。差し戻しの行には、GitHubから取得した指摘本文と差し戻し時刻を付ける。
     * 差し戻しの行が無ければGitHubにも触れない。
     */
    public List<MyArticleReviewResponse> myReviews(Long projectId, Long actorId) {
        List<MyArticleReviewResponse> result = new ArrayList<>();
        GithubAccess access = null;
        for (ArticleReview review : repository
                .findByProjectIdAndSubmittedByUserIdOrderBySubmittedAtDesc(projectId, actorId)) {
            String rejectComment = null;
            if (review.getState() == ArticleReviewState.CHANGES_REQUESTED && review.getRejectCommentId() != null) {
                if (access == null) {
                    access = projectServiceClient.resolveGithubAccess(projectId, actorId);
                }
                rejectComment = githubClient.findIssueComment(access, review.getRejectCommentId())
                        .map(GithubIssueComment::body)
                        .orElse(null);
            }
            boolean rejected = review.getState() == ArticleReviewState.CHANGES_REQUESTED;
            result.add(MyArticleReviewResponse.of(
                    review.getGithubPrNumber(), review.getArticleSlug(), review.getState(),
                    review.getSubmittedAt(), rejectComment, rejected ? review.getRejectedAt() : null));
        }
        return result;
    }

    /** 実施者(Let's Blogのユーザー)が読み取れる見出しを付けた、PRコメントの本文。 */
    private static String commentBody(Long actorId, String actorEmail, String comment) {
        String actor = actorEmail == null || actorEmail.isBlank()
                ? "Let's Blog ユーザー ID " + actorId
                : actorEmail + " (Let's Blog ユーザー ID " + actorId + ")";
        return "**記事の差し戻し** — 差し戻し実施者: " + actor + "\n\n" + comment;
    }
}
