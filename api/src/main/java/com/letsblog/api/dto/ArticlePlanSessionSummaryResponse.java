package com.letsblog.api.dto;

import java.time.LocalDateTime;

public record ArticlePlanSessionSummaryResponse(
        Long id,
        String title,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
