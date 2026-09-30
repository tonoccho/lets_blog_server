package com.letsblog.publishing.github;

/** PR一覧の1件。応答JSONの形もこのレコードのまま(issue #1337)。 */
public record GithubPullRequestSummary(int number, String title, String headBranch, String createdAt, String url) {
}
