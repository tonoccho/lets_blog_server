package com.letsblog.common.messaging;

import java.io.Serializable;
import java.time.Instant;

/**
 * 投稿が公開された(issue #580)。発行元: publishing-service(未抽出の間はlegacy-apiのPostPublishServiceが
 * 代行)。購読: content-service。{@code siteId}+{@code wpPostId}が投稿の識別キー(postsテーブルの
 * 所有権はcontent-serviceにあり、legacy-api側は数値postIdを保持しないため、既存の内部ブリッジ
 * (findBySiteAndWpPostId)と同じキーを使う)。
 */
public record PostPublishedEvent(
        String eventId,
        Instant occurredAt,
        Long siteId,
        Long projectId,
        String wpPostId,
        String url,
        String status
) implements DomainEvent, Serializable {
}
