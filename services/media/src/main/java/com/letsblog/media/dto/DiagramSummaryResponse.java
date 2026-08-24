package com.letsblog.media.dto;

import java.time.LocalDateTime;

public record DiagramSummaryResponse(
        Long id,
        Long projectId,
        String name,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
