package com.letsblog.api.service;

import com.letsblog.api.domain.AuditLog;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.repository.AuditLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Service
@Slf4j
public class AuditLogService {

    private static final int RETENTION_DAYS = 365;

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public void log(Long userId, AuditLogAction action, String resourceType,
                     Long resourceId, String changes, String remoteIp, String userAgent) {
        AuditLog auditLog = new AuditLog();
        auditLog.setUserId(userId);
        auditLog.setAction(action);
        auditLog.setResourceType(resourceType);
        auditLog.setResourceId(resourceId);
        auditLog.setChanges(changes);
        auditLog.setRemoteIp(remoteIp);
        auditLog.setUserAgent(userAgent);

        auditLogRepository.save(auditLog);
        log.info("Audit log recorded: action={}, userId={}, resource={}/{}", action, userId, resourceType, resourceId);
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
     * 1年以上前のログを削除する。毎日午前3時に自動実行する。
     */
    @Scheduled(cron = "0 0 3 * * *")
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
