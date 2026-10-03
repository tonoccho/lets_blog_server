package com.letsblog.ai.dto;

import java.time.Instant;

public record ArticlePlanSessionSummaryResponse(
        Long id,
        String title,
        Integer githubIssueNumber,
        Instant createdAt,
        Instant updatedAt
) {
}
