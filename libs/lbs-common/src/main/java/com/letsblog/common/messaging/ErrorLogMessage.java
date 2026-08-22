package com.letsblog.common.messaging;

import java.io.Serializable;

/** RabbitMQ経由でlog-writerサービスへ送信するフロントエンドエラーログのペイロード(issue #466)。 */
public record ErrorLogMessage(
        String message,
        String stack,
        String componentStack,
        String level,
        String context,
        String url,
        String userAgent,
        String timestamp,
        String createdAt
) implements Serializable {
}
