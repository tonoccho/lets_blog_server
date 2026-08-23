package com.letsblog.common.messaging;

import java.io.Serializable;

/**
 * RabbitMQ経由でlog-writerサービスへ送信するフロントエンドエラーログのペイロード(issue #466)。
 *
 * <p>{@code userId}/{@code actorKeycloakSub}はissue #569で追加。従来フロントエンドエラーログは
 * actor概念を持たなかったが、監査ログ・操作ログと同様にJWTから解決したローカルUser id(userId)と、
 * ローカルUser解決とは独立にJWTのsubクレームをそのまま保持するactorKeycloakSubの両方を持たせる。
 * X-Actor-Idヘッダー経由で記録された場合、あるいは未認証の場合はいずれもnullになる。
 */
public record ErrorLogMessage(
        String message,
        String stack,
        String componentStack,
        String level,
        Long userId,
        String actorKeycloakSub,
        String context,
        String url,
        String userAgent,
        String timestamp,
        String createdAt
) implements Serializable {
}
