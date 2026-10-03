package com.letsblog.media.dto;

import java.time.Instant;

public record DiagramSummaryResponse(
        Long id,
        Long projectId,
        String name,
        Instant createdAt,
        Instant updatedAt
) {
}
