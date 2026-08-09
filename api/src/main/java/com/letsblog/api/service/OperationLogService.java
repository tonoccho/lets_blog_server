package com.letsblog.api.service;

import com.letsblog.api.domain.OperationLog;
import com.letsblog.api.repository.OperationLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Web BFF(Next.js)がバックエンドAPIへ行った全リクエストの技術的な操作ログを記録する。
 * ユーザー自身がデバッグ・サポート/AIへの共有のためにトレースを閲覧・コピーする用途であり、
 * 業務監査用のAuditLogとは目的が異なる。
 */
@Service
@Slf4j
public class OperationLogService {

    private static final int RETENTION_DAYS = 30;

    private final OperationLogRepository repository;

    public OperationLogService(OperationLogRepository repository) {
        this.repository = repository;
    }

    /**
     * REQUIRES_NEWで独立した書き込みトランザクションとして実行する。
     * 呼び出し元のAPIリクエスト処理が失敗・ロールバックしてもログ記録自体は成功させる必要があるため。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(OperationLog entry) {
        repository.save(entry);
    }

    @Transactional(readOnly = true)
    public Page<OperationLog> findByUser(Long userId, Pageable pageable) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    @Transactional(readOnly = true)
    public List<OperationLog> findTrace(Long userId, String operationId) {
        return repository.findByUserIdAndOperationIdOrderByCreatedAtAsc(userId, operationId);
    }

    /**
     * 30日以上前のログを削除する。毎日UTC 03:00に自動実行する(監査ログの削除時刻とずらし、負荷を分散する)。
     */
    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    @Transactional
    public void deleteOldLogs() {
        LocalDateTime threshold = LocalDateTime.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
        List<OperationLog> oldLogs = repository.findByCreatedAtBefore(threshold);
        if (!oldLogs.isEmpty()) {
            repository.deleteAll(oldLogs);
            log.info("Deleted {} old operation logs before {}", oldLogs.size(), threshold);
        }
    }
}
