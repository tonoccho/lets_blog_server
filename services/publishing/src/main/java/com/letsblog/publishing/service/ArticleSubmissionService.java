package com.letsblog.publishing.service;

import com.letsblog.publishing.client.ProjectServiceClient.GithubAccess;
import com.letsblog.publishing.domain.ArticleReview;
import com.letsblog.publishing.dto.ArticleSubmissionRequest;
import com.letsblog.publishing.dto.ArticleSubmissionResponse;
import com.letsblog.publishing.github.GithubPullRequestClient;
import com.letsblog.publishing.github.GithubPullRequestSummary;
import com.letsblog.publishing.repository.ArticleReviewRepository;
import org.springframework.stereotype.Service;

/**
 * 記事提出(issue #1339、Epic #1333)。プッシュ済みのheadブランチのPull Requestをサーバが作り、
 * 提出者(Let's Blogのユーザー)を{@code article_reviews}へ記録する。
 *
 * <p>GitHubへの呼び出し(リモートI/O)をトランザクションの外で行うため、このクラスは
 * {@code @Transactional}にしない。記録は{@link ArticleReviewRepository#save}の1回(自身のトランザクション)だけ。
 */
@Service
public class ArticleSubmissionService {

    private final GithubPullRequestClient githubPullRequestClient;
    private final ArticleReviewRepository repository;

    public ArticleSubmissionService(GithubPullRequestClient githubPullRequestClient,
            ArticleReviewRepository repository) {
        this.githubPullRequestClient = githubPullRequestClient;
        this.repository = repository;
    }

    /**
     * headブランチを提出する。同じheadに開いているPRが既にあれば新規作成せずそのPRを返し、状態を提出済みへ戻す。
     * 再提出では提出者を書き換えない(記録が無いPRのときだけ、呼んだ利用者で記録を作る)。
     *
     * @throws BranchNotFoundException headブランチがGitHubに無い
     */
    public ArticleSubmissionResponse submit(
            Long projectId, Long actorId, GithubAccess access, ArticleSubmissionRequest request) {
        String head = request.headBranch();
        if (!githubPullRequestClient.branchExists(access, head)) {
            throw new BranchNotFoundException(
                    "ブランチが見つかりません: " + head + " (" + access.owner() + "/" + access.repo() + ")。"
                            + "先にブランチをGitHubへプッシュしてください");
        }
        GithubPullRequestSummary existing = githubPullRequestClient.listOpenPullRequests(access).stream()
                .filter(pr -> head.equals(pr.headBranch()))
                .findFirst()
                .orElse(null);
        boolean created = existing == null;
        GithubPullRequestSummary pullRequest = created ? createPullRequest(access, request) : existing;

        ArticleReview review = repository
                .findByProjectIdAndGithubPrNumber(projectId, pullRequest.number())
                .orElseGet(() -> newReview(projectId, actorId, pullRequest.number(), request));
        review.markSubmitted();
        repository.save(review);
        return new ArticleSubmissionResponse(
                pullRequest.number(), pullRequest.url(), review.getState(), review.getSubmittedByUserId(), created);
    }

    private GithubPullRequestSummary createPullRequest(GithubAccess access, ArticleSubmissionRequest request) {
        String base = githubPullRequestClient.getDefaultBranch(access);
        String title = "記事提出: " + request.articleSlug() + " (#" + request.githubIssueNumber() + ")";
        String body = "Closes #" + request.githubIssueNumber() + "\n\n記事スラッグ: " + request.articleSlug();
        return githubPullRequestClient.createPullRequest(access, title, body, request.headBranch(), base);
    }

    private static ArticleReview newReview(
            Long projectId, Long actorId, int prNumber, ArticleSubmissionRequest request) {
        ArticleReview review = new ArticleReview();
        review.setProjectId(projectId);
        review.setGithubPrNumber(prNumber);
        review.setGithubIssueNumber(request.githubIssueNumber());
        review.setArticleSlug(request.articleSlug());
        review.setSubmittedByUserId(actorId);
        return review;
    }
}
