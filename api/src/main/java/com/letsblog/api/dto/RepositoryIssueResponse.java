package com.letsblog.api.dto;

public record RepositoryIssueResponse(
        int number,
        String title,
        String htmlUrl,
        String state
) {
}
