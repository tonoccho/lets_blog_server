package com.letsblog.common.messaging;

import java.io.Serializable;

/** RabbitMQ経由でlog-writerサービスへ送信する監査ログのペイロード(issue #466)。 */
public record AuditLogMessage(
        Long userId,
        String action,
        String resourceType,
        Long resourceId,
        String changes,
        String remoteIp,
        String userAgent,
        String createdAt
) implements Serializable {
}
