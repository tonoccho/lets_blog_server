package com.letsblog.api.dto;

import com.letsblog.api.cms.CmsType;
import com.letsblog.api.domain.Site;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * サイト管理画面向けの詳細レスポンス。credentialsには秘匿情報を除いた設定値
 * (baseUrl, username, sshHost等)のみを含み、appPassword/sshPrivateKeyPem等の
 * シークレットは値を返さず、configuredSecretFieldsに設定済みかどうかのみを含める。
 */
public record SiteDetailResponse(
        Long id,
        String name,
        String siteKey,
        CmsType cmsType,
        String baseUrl,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        boolean managedWordpress,
        boolean sshConfigured,
        Map<String, String> credentials,
        List<String> configuredSecretFields
) {
    public static SiteDetailResponse from(
            Site site, boolean sshConfigured, Map<String, String> credentials, List<String> configuredSecretFields) {
        return new SiteDetailResponse(
                site.getId(),
                site.getName(),
                site.getSiteKey(),
                site.getCmsType(),
                site.getBaseUrl(),
                site.getCreatedAt(),
                site.getUpdatedAt(),
                site.isManagedWordpress(),
                sshConfigured,
                credentials,
                configuredSecretFields
        );
    }
}
