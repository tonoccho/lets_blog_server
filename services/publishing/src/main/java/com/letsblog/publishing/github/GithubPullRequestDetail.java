package com.letsblog.publishing.github;

/**
 * PR詳細のうち後続処理(記事取得・マージ)が使う項目。{@code mergeable}はGitHubが計算中だとnullで返る。
 */
public record GithubPullRequestDetail(int number, String headSha, String headRef, Boolean mergeable, boolean merged) {
}
