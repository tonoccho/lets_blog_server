package com.letsblog.common.messaging;

import java.io.Serializable;
import java.time.Instant;

/**
 * プロジェクトが削除された(issue #580)。発行元: project-service(issue #577で抽出済み)。購読: 全サービス
 * (project_id を外部キーとして保持する設定・データを持つサービスは、この
 * イベントを受けて自スキーマ内の該当行を削除する)。
 *
 * <p>ADR-0004によりproject_ai_settings(ai-service)/analytics_credentials(analytics-service)/
 * project_content_settings(content-service)はprojects(legacy-api)へのクロススキーマFKを持てず、
 * プロジェクト削除時のON DELETE CASCADEが効かなくなっている(スキーマ分割前は同一スキーマ内FKで
 * 連動削除されていたが、抽出後は連動しない実装ギャップとして残っていた)。このイベントはその
 * ギャップを埋める非同期の代替手段。
 */
public record ProjectDeletedEvent(
        String eventId,
        Instant occurredAt,
        Long projectId
) implements DomainEvent, Serializable {
}
