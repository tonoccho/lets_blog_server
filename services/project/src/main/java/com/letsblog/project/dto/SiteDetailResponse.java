package com.letsblog.project.dto;

import com.letsblog.project.cms.CmsType;
import com.letsblog.project.domain.Site;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SiteDetailResponse(
        Long id,
        String name,
        String siteKey,
        CmsType cmsType,
        String baseUrl,
        Instant createdAt,
        Instant updatedAt,
        boolean managedWordpress,
        boolean sshConfigured,
        Map<String, String> credentials,
        List<String> configuredSecretFields,
        String adminPath
) {
    public static SiteDetailResponse from(
            Site site, boolean sshConfigured, Map<String, String> credentials, List<String> configuredSecretFields) {
        return new SiteDetailResponse(
                site.getId(),
                site.getName(),
                site.getSiteKey(),
                site.getCmsType(),
                site.getBaseUrl(),
                UtcDateTimes.toInstant(site.getCreatedAt()),
                UtcDateTimes.toInstant(site.getUpdatedAt()),
                site.isManagedWordpress(),
                sshConfigured,
                credentials,
                configuredSecretFields,
                site.getAdminPath()
        );
    }
}
