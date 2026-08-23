package com.letsblog.api.service;

import com.letsblog.common.messaging.LogExchanges;
import com.letsblog.api.domain.AuditLog;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.common.messaging.AuditLogMessage;
import com.letsblog.api.repository.AuditLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Service
@Slf4j
public class AuditLogService {

    private static final int RETENTION_DAYS = 365;

    private final AuditLogRepository auditLogRepository;
    private final RabbitTemplate rabbitTemplate;

    public AuditLogService(AuditLogRepository auditLogRepository, RabbitTemplate rabbitTemplate) {
        this.auditLogRepository = auditLogRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * ログメッセージキューイング(issue #466)。キューへの発行を優先し、記録自体はlog-writer
     * サービスに委譲する。発行に失敗した場合のみ、ログ欠落を防ぐためこのAPIサーバー自身が
     * 従来通り同期的にDBへ書き込む(REQUIRES_NEWで独立した書き込みトランザクションとして実行し、
     * 呼び出し元(@AuditLogが付いたメソッド)が読み取り専用トランザクション中でも記録自体は
     * 書き込みとして成功させる)。
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
            log.warn("監査ログのキュー発行に失敗したため、同期DB書き込みへフォールバックします", e);
            AuditLog auditLog = new AuditLog();
            auditLog.setUserId(userId);
            auditLog.setActorKeycloakSub(actorKeycloakSub);
            auditLog.setAction(action);
            auditLog.setResourceType(resourceType);
            auditLog.setResourceId(resourceId);
            auditLog.setChanges(changes);
            auditLog.setRemoteIp(remoteIp);
            auditLog.setUserAgent(userAgent);
            auditLogRepository.save(auditLog);
        }
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> findByUserId(Long userId, Pageable pageable) {
        return auditLogRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> findByAction(AuditLogAction action, Pageable pageable) {
        return auditLogRepository.findByActionOrderByCreatedAtDesc(action, pageable);
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> findByDateRange(LocalDateTime start, LocalDateTime end, Pageable pageable) {
        return auditLogRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end, pageable);
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> findAll(Pageable pageable) {
        return auditLogRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    /**
     * 1年以上前のログを削除する。毎日UTC 02:00に自動実行する
     * (業務時間を避けた低負荷時間帯として指定、spec/phase5/02-audit-log-archival.md 参照)。
     */
    @Scheduled(cron = "0 0 2 * * *", zone = "UTC")
    @Transactional
    public void deleteOldLogs() {
        LocalDateTime threshold = LocalDateTime.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
        var oldLogs = auditLogRepository.findByCreatedAtBefore(threshold);
        if (!oldLogs.isEmpty()) {
            auditLogRepository.deleteAll(oldLogs);
            log.info("Deleted {} old audit logs before {}", oldLogs.size(), threshold);
        }
    }
}
