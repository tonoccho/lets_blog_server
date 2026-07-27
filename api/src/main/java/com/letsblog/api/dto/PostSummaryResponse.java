package com.letsblog.api.dto;

import java.time.LocalDateTime;

public record PostSummaryResponse(
        Long id,
        Long siteId,
        String siteName,
        String wpPostId,
        String slug,
        String status,
        LocalDateTime lastPublishedAt
) {
}
