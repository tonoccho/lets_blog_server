package com.letsblog.ai.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ArticlePlanSessionDetailResponse(
        Long id,
        String title,
        Integer githubIssueNumber,
        List<PlanChatMessage> history,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
