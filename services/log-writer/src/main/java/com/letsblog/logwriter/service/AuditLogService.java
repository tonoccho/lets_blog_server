package com.letsblog.logwriter.service;

import com.letsblog.logwriter.domain.AuditLog;
import com.letsblog.logwriter.repository.AuditLogRepository;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 監査ログの読み取り・保持期間管理(#572でlegacy-apiから移設)。書き込みはRabbitMQ経由で
 * {@link com.letsblog.logwriter.listener.LogMessageListener}が担う(legacy-api側の
 * AuditLogAspect/AuditLogServiceがキューへ発行したメッセージをここで受信して保存する)。
 */
@Service
@Slf4j
public class AuditLogService {

    private static final int RETENTION_DAYS = 365;

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> findByUserId(Long userId, Pageable pageable) {
        return auditLogRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> findByAction(String action, Pageable pageable) {
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
