package com.letsblog.common.messaging;

import java.io.Serializable;
import java.time.Instant;

/**
 * 投稿が削除(ゴミ箱移動)された(issue #580)。発行元: publishing-service(未抽出の間はlegacy-apiの
 * PostDeleteServiceが代行)。購読: content-service, media-service。{@code siteId}+{@code wpPostId}が
 * 投稿の識別キー({@link PostPublishedEvent}と同じ理由)。
 */
public record PostDeletedEvent(
        String eventId,
        Instant occurredAt,
        Long siteId,
        String wpPostId
) implements DomainEvent, Serializable {
}
