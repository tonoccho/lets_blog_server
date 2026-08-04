package com.letsblog.api.dto;

import java.time.LocalDateTime;

public record GeneratedImageSummaryResponse(
        Long id,
        Long projectId,
        String prompt,
        String checkpoint,
        LocalDateTime createdAt
) {
}
