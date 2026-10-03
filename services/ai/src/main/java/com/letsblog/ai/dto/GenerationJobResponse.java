package com.letsblog.ai.dto;

import java.time.Instant;

public record GenerationJobResponse(
        Long id,
        String type,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}
