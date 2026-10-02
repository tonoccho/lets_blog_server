package com.letsblog.publishing.github;

/** GitHubのIssue/Pull Requestコメント1件(issue #1344)。{@code body}はGitHub上の本文そのまま。 */
public record GithubIssueComment(long id, String body) {
}
