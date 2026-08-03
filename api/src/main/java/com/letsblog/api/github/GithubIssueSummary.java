package com.letsblog.api.github;

public record GithubIssueSummary(int number, String title, String htmlUrl, String state) {
}
