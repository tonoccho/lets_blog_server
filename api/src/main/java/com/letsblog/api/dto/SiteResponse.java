package com.letsblog.api.dto;

import com.letsblog.api.domain.Site;

import java.time.LocalDateTime;

public record SiteResponse(
        Long id,
        String name,
        String siteKey,
        String baseUrl,
        String wpUsername,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static SiteResponse from(Site site) {
        return new SiteResponse(
                site.getId(),
                site.getName(),
                site.getSiteKey(),
                site.getBaseUrl(),
                site.getWpUsername(),
                site.getCreatedAt(),
                site.getUpdatedAt()
        );
    }
}
