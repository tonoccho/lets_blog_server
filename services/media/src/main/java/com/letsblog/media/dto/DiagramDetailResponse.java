package com.letsblog.media.dto;

import java.time.Instant;

public record DiagramDetailResponse(
        Long id,
        Long projectId,
        String name,
        String xml,
        String svg,
        Instant createdAt,
        Instant updatedAt
) {
}
