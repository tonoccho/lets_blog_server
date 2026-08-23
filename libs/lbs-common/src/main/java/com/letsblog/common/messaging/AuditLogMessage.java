package com.letsblog.common.messaging;

import java.io.Serializable;

/**
 * RabbitMQ経由でlog-writerサービスへ送信する監査ログのペイロード(issue #466)。
 *
 * <p>{@code actorKeycloakSub}はissue #569で追加。JWTのsubクレームをローカルUser解決(userId)とは
 * 独立に保持し、User未同期・削除済みでも監査証跡の追跡性を保つ。X-Actor-Idヘッダー経由で
 * 記録された場合はJWTが存在しないためnullになる。
 */
public record AuditLogMessage(
        Long userId,
        String actorKeycloakSub,
        String action,
        String resourceType,
        Long resourceId,
        String changes,
        String remoteIp,
        String userAgent,
        String createdAt
) implements Serializable {
}
