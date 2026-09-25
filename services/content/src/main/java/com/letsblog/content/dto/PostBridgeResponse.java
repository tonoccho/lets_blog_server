package com.letsblog.content.dto;

import java.time.LocalDateTime;

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
        LocalDateTime publishScheduledAt,
        LocalDateTime lastPublishedAt) {
}
