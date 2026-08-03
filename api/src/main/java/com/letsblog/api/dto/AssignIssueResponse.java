package com.letsblog.api.dto;

public record AssignIssueResponse(
        int issueNumber,
        String htmlUrl,
        String assignedLogin
) {
}
