package com.letsblog.api.dto;

import java.time.LocalDateTime;

public record GenerationJobDetailResponse(
        Long id,
        String type,
        String status,
        String requestPayload,
        String resultPayload,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
