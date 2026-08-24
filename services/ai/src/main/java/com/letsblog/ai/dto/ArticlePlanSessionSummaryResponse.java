package com.letsblog.ai.dto;

import java.time.LocalDateTime;

public record ArticlePlanSessionSummaryResponse(
        Long id,
        String title,
        Integer githubIssueNumber,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
