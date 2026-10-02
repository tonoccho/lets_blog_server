package com.letsblog.common.messaging;

import java.io.Serializable;
import java.time.Instant;

/**
 * プロジェクトにサイト(環境)が紐付けられた(issue #1324)。発行元: project-service
 * ({@code ProjectService#bindEnvironment})。購読: identity-service。
 *
 * <p>ADR-0004によりidentity-serviceはprojects/sitesのスキーマを直接参照できない。サイト紐付け前に
 * 追加されたメンバーのWordPressユーザーは、この通知を受けて初めて、紐付けられたサイトに
 * 作られる(identity-service側は{@code projectId}のメンバーを自スキーマの{@code project_users}から引く)。
 * 受信側が必要とするのは{@code projectId}と{@code siteId}だけで、紐付け後のプロジェクトの状態には
 * 依存しない(発行時点ではproject-serviceのトランザクションが未コミットでもよい)。
 */
public record ProjectEnvironmentBoundEvent(
        String eventId,
        Instant occurredAt,
        Long projectId,
        Long siteId
) implements DomainEvent, Serializable {
}
