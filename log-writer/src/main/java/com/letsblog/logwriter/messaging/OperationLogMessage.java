package com.letsblog.logwriter.messaging;

/** apiサーバーがRabbitMQへ送信する操作ログのペイロード(issue #466)。 */
public record OperationLogMessage(
        String operationId,
        Long userId,
        String method,
        String path,
        Integer statusCode,
        Long durationMs,
        boolean success,
        String errorMessage,
        String createdAt
) {
}
