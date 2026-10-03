package com.letsblog.project.dto;

import com.letsblog.project.cms.CmsType;
import com.letsblog.project.domain.Site;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * legacy-apiに残るドメイン(一括管理/環境間比較・投稿publish・記事プレビュー等)が、サイトの基本情報
 * (認証情報を除く)を参照するための内部API応答(issue #577 stage3)。CMS認証情報自体は
 * {@link SiteCredentialsResponse}(既存、issue #577受入基準)で別途取得する。
 */
public record SiteBridgeResponse(
        Long id, String siteKey, String name, String baseUrl, CmsType cmsType, boolean managedWordpress,
        String wpSlug, Instant createdAt, Instant updatedAt) {

    public static SiteBridgeResponse from(Site site) {
        return new SiteBridgeResponse(
                site.getId(), site.getSiteKey(), site.getName(), site.getBaseUrl(), site.getCmsType(),
                site.isManagedWordpress(), site.getWpSlug(), toInstant(site.getCreatedAt()), toInstant(site.getUpdatedAt()));
    }

    // DB / エンティティの LocalDateTime は UTC の壁時計(#1257)。内部ブリッジも Z 終端 RFC 3339 で返す(#1541)。
    private static Instant toInstant(LocalDateTime utcWallClock) {
        return utcWallClock == null ? null : utcWallClock.toInstant(ZoneOffset.UTC);
    }
}
