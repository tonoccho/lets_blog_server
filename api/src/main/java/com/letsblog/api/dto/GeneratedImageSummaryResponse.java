package com.letsblog.api.dto;

import java.time.LocalDateTime;
import java.util.List;

public record GeneratedImageSummaryResponse(
        Long id,
        Long projectId,
        String prompt,
        String checkpoint,
        LocalDateTime createdAt,
        List<String> tags,
        String provider
) {
}
