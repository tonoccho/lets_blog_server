package com.letsblog.common.messaging;

import java.io.Serializable;
import java.time.Instant;

/**
 * サイトが削除された(issue #580)。発行元: project-service(未抽出の間はlegacy-apiの
 * WordPressSiteProvisioningServiceが代行)。購読: publishing(未抽出の間は対象外), content-service。
 *
 * <p>content-serviceは既に{@code ContentServiceClient#deletePostsBySite}経由の同期内部ブリッジで
 * サイト削除時の投稿一括削除を受けているが、このイベントはその非同期版(将来的な追加購読者・
 * 同期呼び出し失敗時のフォールバック経路)として並行して発行する。両経路とも
 * {@code postRepository.deleteBySiteId}へ帰着するため冪等(再実行しても副作用なし)。
 */
public record SiteDeletedEvent(
        String eventId,
        Instant occurredAt,
        Long siteId
) implements DomainEvent, Serializable {
}
