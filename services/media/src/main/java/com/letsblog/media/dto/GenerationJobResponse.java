package com.letsblog.media.dto;

import java.time.LocalDateTime;

public record GenerationJobResponse(
        Long id,
        String type,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
