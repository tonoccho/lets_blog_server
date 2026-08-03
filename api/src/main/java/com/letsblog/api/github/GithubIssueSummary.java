package com.letsblog.api.github;

import java.util.List;

public record GithubIssueSummary(int number, String title, String htmlUrl, String state, List<String> assignees) {
}
