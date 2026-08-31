package com.letsblog.identity.service;

import com.letsblog.common.messaging.AuditLogMessage;
import com.letsblog.common.messaging.LogExchanges;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * 監査ログの記録(書き込み)。{@code audit_logs}テーブルの所有権はlog-writerにあり(#572)、
 * プロデューサーはRabbitMQへ流すだけである。media-serviceの同名クラス(#573 stage3)と同じ形。
 *
 * <p>legacy-api版はAOP({@code @AuditLog} + {@code AuditLogAspect})で記録していたが、
 * identity-serviceが記録するアクションは{@code project_users}の3つだけなので、
 * アスペクトごと持ち込まず明示的な呼び出しにした(issue #583)。
 * アスペクト版が入れていた{@code resourceId}は「引数の最初のLong」= projectId、
 * {@code changes}は「戻り値のJSON」だが対象メソッドはいずれもvoidを返すためnullだった。
 * ここではその実挙動をそのまま明示的に渡している。
 */
@Service
@Slf4j
public class AuditLogService {

    public static final String ACTION_PROJECT_USER_ADDED = "PROJECT_USER_ADDED";
    public static final String ACTION_PROJECT_USER_ROLE_UPDATED = "PROJECT_USER_ROLE_UPDATED";
    public static final String ACTION_PROJECT_USER_REMOVED = "PROJECT_USER_REMOVED";

    private static final String RESOURCE_TYPE_PROJECT_USER = "PROJECT_USER";

    private final RabbitTemplate rabbitTemplate;
    private final CurrentActorService currentActorService;

    public AuditLogService(RabbitTemplate rabbitTemplate, CurrentActorService currentActorService) {
        this.rabbitTemplate = rabbitTemplate;
        this.currentActorService = currentActorService;
    }

    /**
     * {@code project_users}に対する操作を記録する。記録の失敗は呼び出し元の処理を失敗させない
     * (legacy-apiのアスペクトも例外を握り潰してwarnログに留めていた)。
     */
    public void logProjectUserAction(String action, Long projectId) {
        try {
            AuditLogMessage message = new AuditLogMessage(
                    currentActorService.getCurrentActorId(),
                    currentActorService.getCurrentActorKeycloakSub(),
                    action,
                    RESOURCE_TYPE_PROJECT_USER,
                    projectId,
                    null,
                    currentActorService.getRemoteIp(),
                    currentActorService.getUserAgent(),
                    LocalDateTime.now().toString());
            rabbitTemplate.convertAndSend(LogExchanges.LOG_EXCHANGE, LogExchanges.AUDIT_LOG_ROUTING_KEY, message);
            log.info("Audit log published to queue: action={}, projectId={}", action, projectId);
        } catch (RuntimeException e) {
            log.warn("監査ログの記録に失敗しました: action={}, projectId={}", action, projectId, e);
        }
    }
}
