package com.letsblog.api.dto;

import java.util.List;

public record RepositoryIssueResponse(
        int number,
        String title,
        String htmlUrl,
        String state,
        List<String> assignees
) {
}
