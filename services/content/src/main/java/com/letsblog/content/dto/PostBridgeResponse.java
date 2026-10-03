package com.letsblog.content.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * legacy-api側のPostPublishService/PostDeleteService(issue #575まで引き続きlegacy-apiに残る)向けの
 * 内部ブリッジレスポンス。Postエンティティのうち、公開パイプラインが直接参照するフィールドのみを返す
 * (issue #576)。
 */
public record PostBridgeResponse(
        Long siteId,
        String wpPostId,
        String slug,
        String status,
        String uploadedImagesJson,
        String categories,
        Instant publishScheduledAt,
        Instant lastPublishedAt) {

    // DB / エンティティの LocalDateTime は UTC の壁時計(#1257)。内部ブリッジも Z 終端 RFC 3339 で返す(#1541)。
    public static Instant toInstant(LocalDateTime utcWallClock) {
        return utcWallClock == null ? null : utcWallClock.toInstant(ZoneOffset.UTC);
    }
}
