package com.letsblog.api.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ArticlePlanSessionDetailResponse(
        Long id,
        String title,
        List<PlanChatMessage> history,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
