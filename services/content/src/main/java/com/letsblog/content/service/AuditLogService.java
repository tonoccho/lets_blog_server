package com.letsblog.content.service;

import com.letsblog.common.messaging.AuditLogMessage;
import com.letsblog.common.messaging.LogExchanges;
import com.letsblog.content.domain.AuditLogAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 監査ログの記録(書き込み)。legacy-apiのAuditLogService(#572でaudit_logsテーブルの所有権を
 * log-writerへ完全移管した際の実装)と同じプロデューサー側パターンを踏襲する(media-service(#573
 * stage3)に続き、issue #576)。audit_logsテーブルの所有権・読み取りはlog-writerにあり、
 * content-serviceはこのテーブルへ直接アクセスしない。
 */
@Service
@Slf4j
public class AuditLogService {

    private final RabbitTemplate rabbitTemplate;

    public AuditLogService(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void log(Long userId, String actorKeycloakSub, AuditLogAction action, String resourceType,
                     Long resourceId, String changes, String remoteIp, String userAgent) {
        AuditLogMessage message = new AuditLogMessage(
                userId, actorKeycloakSub, action.name(), resourceType, resourceId, changes, remoteIp, userAgent,
                LocalDateTime.now().toString());

        try {
            rabbitTemplate.convertAndSend(LogExchanges.LOG_EXCHANGE, LogExchanges.AUDIT_LOG_ROUTING_KEY, message);
            log.info("Audit log published to queue: action={}, userId={}, resource={}/{}",
                    action, userId, resourceType, resourceId);
        } catch (AmqpException e) {
            log.error("監査ログのキュー発行に失敗し、記録できませんでした: "
                    + "action={}, userId={}, resource={}/{}, changes={}, remoteIp={}, userAgent={}",
                    action, userId, resourceType, resourceId, changes, remoteIp, userAgent, e);
        }
    }
}
