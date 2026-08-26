package com.letsblog.common.messaging;

import java.io.Serializable;
import java.time.Instant;

/**
 * サイトが削除された(issue #580)。発行元: project-service(issue #577で抽出済み)。購読: publishing
 * (未抽出の間は対象外), content-service。
 *
 * <p>project-serviceにcontent-serviceへの同期内部ブリッジがまだ無いため(issue #577の既知の制限)、
 * 現状はこのイベント配送のみが投稿一括削除の経路(将来、同期ブリッジを追加する場合は
 * {@code postRepository.deleteBySiteId}へ帰着させ、このイベント経路と冪等にすること)。
 */
public record SiteDeletedEvent(
        String eventId,
        Instant occurredAt,
        Long siteId
) implements DomainEvent, Serializable {
}
