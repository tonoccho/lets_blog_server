package com.letsblog.media.service;

import com.letsblog.common.messaging.AuditLogMessage;
import com.letsblog.common.messaging.LogExchanges;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 監査ログの記録(書き込み)。legacy-apiのAuditLogService(#572でaudit_logsテーブルの
 * 所有権をlog-writerへ完全移管した際の実装)と同じプロデューサー側パターンを踏襲する
 * (#573 stage3、media-serviceが監査ログを発行する初めてのサービス)。
 *
 * <p>legacy-api版と異なり、{@code action}はenum({@code AuditLogAction}、多数の非media関連
 * アクションを含む共有enum)ではなく、media-serviceが実際に記録する唯一のアクション
 * ({@code MEDIA_GARBAGE_COLLECTED})に限定した文字列として受け取る(不要な大きなenumの複製を
 * 避けるための意図的な簡略化。{@link com.letsblog.common.messaging.AuditLogMessage#action}
 * 自体がString型のため、呼び出し側でenum化する必然性はない)。
 */
@Service
@Slf4j
public class AuditLogService {

    public static final String ACTION_MEDIA_GARBAGE_COLLECTED = "MEDIA_GARBAGE_COLLECTED";

    private final RabbitTemplate rabbitTemplate;

    public AuditLogService(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void log(Long userId, String actorKeycloakSub, String action, String resourceType,
                     Long resourceId, String changes, String remoteIp, String userAgent) {
        AuditLogMessage message = new AuditLogMessage(
                userId, actorKeycloakSub, action, resourceType, resourceId, changes, remoteIp, userAgent,
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
