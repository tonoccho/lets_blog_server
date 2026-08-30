package com.letsblog.project.dto;

import com.letsblog.project.cms.CmsType;
import com.letsblog.project.domain.Site;
import java.time.LocalDateTime;

/**
 * legacy-apiに残るドメイン(一括管理/環境間比較・投稿publish・記事プレビュー等)が、サイトの基本情報
 * (認証情報を除く)を参照するための内部API応答(issue #577 stage3)。CMS認証情報自体は
 * {@link SiteCredentialsResponse}(既存、issue #577受入基準)で別途取得する。
 */
public record SiteBridgeResponse(
        Long id, String siteKey, String name, String baseUrl, CmsType cmsType, boolean managedWordpress,
        String wpSlug, LocalDateTime createdAt, LocalDateTime updatedAt) {

    public static SiteBridgeResponse from(Site site) {
        return new SiteBridgeResponse(
                site.getId(), site.getSiteKey(), site.getName(), site.getBaseUrl(), site.getCmsType(),
                site.isManagedWordpress(), site.getWpSlug(), site.getCreatedAt(), site.getUpdatedAt());
    }
}
