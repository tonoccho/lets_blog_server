package com.letsblog.content.dto;

import java.time.Instant;
import java.util.List;

public record PostSummaryResponse(
        Long id,
        Long siteId,
        String siteName,
        String wpPostId,
        String slug,
        String status,
        Instant lastPublishedAt,
        List<String> categories,
        Instant publishScheduledAt
) {
}
