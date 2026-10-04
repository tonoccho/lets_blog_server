package com.letsblog.project.dto;

import com.letsblog.project.cms.CmsType;
import com.letsblog.project.domain.Site;
import java.time.Instant;

public record SiteResponse(
        Long id,
        String name,
        String siteKey,
        CmsType cmsType,
        String baseUrl,
        Instant createdAt,
        Instant updatedAt,
        String connectionCheckStatus,
        boolean managedWordpress,
        boolean sshConfigured,
        String adminPath,
        LetsblogSyncState letsblogSync
) {
    public SiteResponse(
            Long id, String name, String siteKey, CmsType cmsType, String baseUrl, Instant createdAt,
            Instant updatedAt, String connectionCheckStatus, boolean managedWordpress, boolean sshConfigured,
            String adminPath) {
        this(id, name, siteKey, cmsType, baseUrl, createdAt, updatedAt, connectionCheckStatus, managedWordpress,
                sshConfigured, adminPath, null);
    }

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
                UtcDateTimes.toInstant(site.getCreatedAt()),
                UtcDateTimes.toInstant(site.getUpdatedAt()),
                connectionOk == null ? null : (connectionOk ? "SUCCESS" : "FAILED"),
                site.isManagedWordpress(),
                sshConfigured,
                site.getAdminPath(),
                LetsblogSyncState.from(site)
        );
    }
}
