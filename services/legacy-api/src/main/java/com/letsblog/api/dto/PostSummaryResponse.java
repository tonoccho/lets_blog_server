package com.letsblog.api.dto;

import java.time.LocalDateTime;
import java.util.List;

public record PostSummaryResponse(
        Long id,
        Long siteId,
        String siteName,
        String wpPostId,
        String slug,
        String status,
        LocalDateTime lastPublishedAt,
        List<String> categories,
        LocalDateTime publishScheduledAt
) {
}
