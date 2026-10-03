package com.letsblog.ai.dto;

import java.time.Instant;

public record GenerationJobDetailResponse(
        Long id,
        String type,
        String status,
        String requestPayload,
        String resultPayload,
        Instant createdAt,
        Instant updatedAt
) {
}
