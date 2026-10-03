package com.letsblog.ai.dto;

import java.time.Instant;
import java.util.List;

public record ArticlePlanSessionDetailResponse(
        Long id,
        String title,
        Integer githubIssueNumber,
        List<PlanChatMessage> history,
        Instant createdAt,
        Instant updatedAt
) {
}
