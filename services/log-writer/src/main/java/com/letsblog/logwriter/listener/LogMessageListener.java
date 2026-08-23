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
        entity.setChanges(message.changes());
        entity.setRemoteIp(message.remoteIp());
        entity.setUserAgent(message.userAgent());
        entity.setCreatedAt(parse(message.createdAt()));
        auditLogRepository.save(entity);
        log.debug("Audit log written: action={}, userId={}", message.action(), message.userId());
    }

    private LocalDateTime parse(String value) {
        return value != null ? LocalDateTime.parse(value) : LocalDateTime.now();
    }
}
