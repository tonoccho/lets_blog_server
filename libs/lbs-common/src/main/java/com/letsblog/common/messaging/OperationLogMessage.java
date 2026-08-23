package com.letsblog.common.messaging;

import java.io.Serializable;

/**
 * RabbitMQ経由でlog-writerサービスへ送信する操作ログのペイロード(issue #466)。
 *
 * <p>{@code actorKeycloakSub}はissue #569で追加。JWTのsubクレームをローカルUser解決(userId)とは
 * 独立に保持し、User未同期・削除済みでも監査証跡の追跡性を保つ。X-Actor-Idヘッダー経由で
 * 記録された場合はJWTが存在しないためnullになる。
 */
public record OperationLogMessage(
        String operationId,
        Long userId,
        String actorKeycloakSub,
        String method,
        String path,
        Integer statusCode,
        Long durationMs,
        boolean success,
        String errorMessage,
        String createdAt
) implements Serializable {
}
