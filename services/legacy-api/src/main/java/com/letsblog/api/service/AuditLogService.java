package com.letsblog.api.service;

import com.letsblog.common.messaging.LogExchanges;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.common.messaging.AuditLogMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 監査ログの記録(書き込み)のみを担う。読み取り・保持期間管理はlog-writerサービスへ完全移管した
 * (issue #572)。audit_logsテーブルのスキーマ所有権・Flywayマイグレーションもlog-writer
 * (lbs_logスキーマ、ADR-0004)にあり、legacy-apiはこのテーブルへ直接アクセスしない。
 */
@Service
@Slf4j
public class AuditLogService {

    private final RabbitTemplate rabbitTemplate;

    public AuditLogService(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * ログメッセージキューイング(issue #466)。キューへの発行を優先し、記録自体はlog-writer
     * サービスに委譲する。#572でaudit_logsテーブルの所有権をlog-writerへ完全移管したため、
     * legacy-api自身はこのテーブルへの直接アクセス手段を持たない。発行に失敗した場合、
     * 従来はこのAPIサーバー自身が同期的にDBへフォールバック書き込みしていたが、その手段が
     * なくなったため、ログ内容をアプリケーションログへ書き出すのみに留める(ベストエフォート。
     * C12(#581)でサービス間同期呼び出しの規約が定まったら、より確実な代替手段を検討する)。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
            log.error("監査ログのキュー発行に失敗し、記録できませんでした(#572でDBフォールバックは廃止): "
                    + "action={}, userId={}, resource={}/{}, changes={}, remoteIp={}, userAgent={}",
                    action, userId, resourceType, resourceId, changes, remoteIp, userAgent, e);
        }
    }
}
