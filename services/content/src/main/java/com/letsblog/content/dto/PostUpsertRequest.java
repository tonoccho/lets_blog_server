package com.letsblog.content.dto;

import java.time.LocalDateTime;

/**
 * legacy-api側のPostPublishService#upsertPostRecordが公開結果をposts行へ反映するための
 * 内部ブリッジリクエスト(issue #576)。
 */
public record PostUpsertRequest(
        Long siteId,
        String wpPostId,
        String slug,
        String status,
        String uploadedImagesJson,
        String categories,
        LocalDateTime publishScheduledAt) {
}
