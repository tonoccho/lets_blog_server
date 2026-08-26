package com.letsblog.project.dto;

import com.letsblog.project.cms.CmsType;
import com.letsblog.project.domain.Site;
import java.time.LocalDateTime;

public record SiteResponse(
        Long id,
        String name,
        String siteKey,
        CmsType cmsType,
        String baseUrl,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String connectionCheckStatus,
        boolean managedWordpress,
        boolean sshConfigured
) {
    public static SiteResponse from(Site site) {
        return from(site, null, false);
    }

    public static SiteResponse from(Site site, Boolean connectionOk) {
        return from(site, connectionOk, false);
    }

    public static SiteResponse from(Site site, Boolean connectionOk, boolean sshConfigured) {
        return new SiteResponse(
                site.getId(),
                site.getName(),
                site.getSiteKey(),
                site.getCmsType(),
                site.getBaseUrl(),
                site.getCreatedAt(),
                site.getUpdatedAt(),
                connectionOk == null ? null : (connectionOk ? "SUCCESS" : "FAILED"),
                site.isManagedWordpress(),
                sshConfigured
        );
    }
}
