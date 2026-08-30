package com.letsblog.ai.dto;

public record AssignIssueResponse(
        int issueNumber,
        String htmlUrl,
        String assignedLogin
) {
}
