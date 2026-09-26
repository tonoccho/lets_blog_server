package com.letsblog.logwriter.listener;

import com.letsblog.logwriter.config.RabbitMqConfig;
import com.letsblog.logwriter.domain.AuditLog;
import com.letsblog.logwriter.domain.FrontendErrorLog;
import com.letsblog.logwriter.domain.OperationLog;
import com.letsblog.common.messaging.AuditLogMessage;
import com.letsblog.common.messaging.ErrorLogMessage;
import com.letsblog.common.messaging.OperationLogMessage;
import com.letsblog.logwriter.repository.AuditLogRepository;
import com.letsblog.logwriter.repository.FrontendErrorLogRepository;
import com.letsblog.logwriter.repository.OperationLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * RabbitMQからログメッセージを受信し、自身が所有するlbs_logスキーマへ記録する
 * (issue #466、スキーマ所有権は#572で本サービスへ完全移管)。
 * リスナーが例外を投げるとキュー側でメッセージが再配送(requeue)されるため、
 * 個々のメッセージ処理失敗はここで握りつぶさずそのまま伝播させる。
 */
@Component
@Slf4j
public class LogMessageListener {

    /** audit_logs.changes(TEXT)の最大バイト数。 */
    private static final int CHANGES_MAX_BYTES = 65_535;

    private final FrontendErrorLogRepository frontendErrorLogRepository;
    private final OperationLogRepository operationLogRepository;
    private final AuditLogRepository auditLogRepository;

    public LogMessageListener(
            FrontendErrorLogRepository frontendErrorLogRepository,
            OperationLogRepository operationLogRepository,
            AuditLogRepository auditLogRepository) {
        this.frontendErrorLogRepository = frontendErrorLogRepository;
        this.operationLogRepository = operationLogRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @RabbitListener(queues = RabbitMqConfig.ERROR_LOG_QUEUE)
    public void onErrorLog(ErrorLogMessage message) {
        FrontendErrorLog entity = new FrontendErrorLog();
        entity.setMessage(message.message());
        entity.setStack(message.stack());
        entity.setComponentStack(message.componentStack());
        entity.setLevel(message.level());
        entity.setUserId(message.userId());
        entity.setActorKeycloakSub(message.actorKeycloakSub());
        entity.setContext(message.context());
        entity.setUrl(message.url());
        entity.setUserAgent(message.userAgent());
        entity.setTimestamp(parse(message.timestamp()));
        entity.setCreatedAt(parse(message.createdAt()));
        frontendErrorLogRepository.save(entity);
        log.debug("Frontend error log written: url={}", message.url());
    }

    @RabbitListener(queues = RabbitMqConfig.OPERATION_LOG_QUEUE)
    public void onOperationLog(OperationLogMessage message) {
        OperationLog entity = new OperationLog();
        entity.setOperationId(message.operationId());
        entity.setUserId(message.userId());
        entity.setActorKeycloakSub(message.actorKeycloakSub());
        entity.setMethod(message.method());
        entity.setPath(message.path());
        entity.setStatusCode(message.statusCode());
        entity.setDurationMs(message.durationMs() != null ? message.durationMs() : 0L);
        entity.setSuccess(message.success());
        entity.setErrorMessage(message.errorMessage());
        entity.setCreatedAt(parse(message.createdAt()));
        operationLogRepository.save(entity);
        log.debug("Operation log written: operationId={}", message.operationId());
    }

    @RabbitListener(queues = RabbitMqConfig.AUDIT_LOG_QUEUE)
    public void onAuditLog(AuditLogMessage message) {
        AuditLog entity = new AuditLog();
        entity.setUserId(message.userId());
        entity.setActorKeycloakSub(message.actorKeycloakSub());
        entity.setAction(message.action());
        entity.setResourceType(message.resourceType());
        entity.setResourceId(message.resourceId());
        entity.setChanges(truncateToColumnLimit(message.changes()));
        entity.setRemoteIp(message.remoteIp());
        entity.setUserAgent(message.userAgent());
        entity.setCreatedAt(parse(message.createdAt()));
        auditLogRepository.save(entity);
        log.debug("Audit log written: action={}, userId={}", message.action(), message.userId());
    }

    /**
     * audit_logs.changes(TEXT)の上限を超える値でINSERTが「Data too long」で失敗し毒メッセージになるのを防ぐ
     * (issue #1246)。UTF-8で上限バイト内に収まる最長のprefixへ切り詰める(文字の途中では切らない)。
     */
    private static String truncateToColumnLimit(String changes) {
        if (changes == null || changes.length() * 3L <= CHANGES_MAX_BYTES) {
            return changes;
        }
        int bytes = 0;
        int index = 0;
        while (index < changes.length()) {
            int codePoint = changes.codePointAt(index);
            int charCount = Character.charCount(codePoint);
            int codePointBytes = codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
            if (bytes + codePointBytes > CHANGES_MAX_BYTES) {
                log.warn("Audit log changes exceeded {} bytes and was truncated", CHANGES_MAX_BYTES);
                return changes.substring(0, index);
            }
            bytes += codePointBytes;
            index += charCount;
        }
        return changes;
    }

    private LocalDateTime parse(String value) {
        return value != null ? LocalDateTime.parse(value) : LocalDateTime.now();
    }
}
