package com.letsblog.publishing.dto;

import com.letsblog.publishing.domain.ArticleReviewState;
import com.letsblog.publishing.github.GithubPullRequestSummary;

/**
 * レビュー待ちPR一覧の1件(issue #1337、#1677)。GitHubから取ったPRの項目はそのまま、
 * {@code state}にそのプロジェクトでのレビュー状態を載せる。{@code article_reviews}に記録の無いPRは{@code null}。
 */
public record ArticleReviewPullRequestResponse(
        int number, String title, String headBranch, String createdAt, String url, ArticleReviewState state) {

    public static ArticleReviewPullRequestResponse of(GithubPullRequestSummary pr, ArticleReviewState state) {
        return new ArticleReviewPullRequestResponse(
                pr.number(), pr.title(), pr.headBranch(), pr.createdAt(), pr.url(), state);
    }
}
