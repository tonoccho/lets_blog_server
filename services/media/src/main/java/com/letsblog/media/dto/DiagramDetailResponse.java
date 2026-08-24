package com.letsblog.media.dto;

import java.time.LocalDateTime;

public record DiagramDetailResponse(
        Long id,
        Long projectId,
        String name,
        String xml,
        String svg,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
