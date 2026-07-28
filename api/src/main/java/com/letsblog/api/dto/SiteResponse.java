package com.letsblog.api.dto;

import com.letsblog.api.cms.CmsType;
import com.letsblog.api.domain.Site;

import java.time.LocalDateTime;

public record SiteResponse(
        Long id,
        String name,
        String siteKey,
        CmsType cmsType,
        String baseUrl,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String connectionCheckStatus
) {
    public static SiteResponse from(Site site) {
        return from(site, null);
    }

    public static SiteResponse from(Site site, Boolean connectionOk) {
        return new SiteResponse(
                site.getId(),
                site.getName(),
                site.getSiteKey(),
                site.getCmsType(),
                site.getBaseUrl(),
                site.getCreatedAt(),
                site.getUpdatedAt(),
                connectionOk == null ? null : (connectionOk ? "SUCCESS" : "FAILED")
        );
    }
}
