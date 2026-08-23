package com.letsblog.api.service;

import com.letsblog.common.messaging.LogExchanges;
import com.letsblog.api.domain.OperationLog;
import com.letsblog.common.messaging.OperationLogMessage;
import com.letsblog.api.repository.OperationLogRepository;
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
    private final RabbitTemplate rabbitTemplate;

    public OperationLogService(OperationLogRepository repository, RabbitTemplate rabbitTemplate) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * ログメッセージキューイング(issue #466)。キューへの発行を優先し、記録自体はlog-writer
     * サービスに委譲する。発行に失敗した場合のみ、ログ欠落を防ぐためこのAPIサーバー自身が
     * 従来通り同期的にDBへ書き込む(REQUIRES_NEWで独立した書き込みトランザクションとして実行し、
     * 呼び出し元のAPIリクエスト処理が失敗・ロールバックしてもログ記録自体は成功させる)。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(OperationLog entry) {
        OperationLogMessage message = new OperationLogMessage(
                entry.getOperationId(),
                entry.getUserId(),
                entry.getActorKeycloakSub(),
                entry.getMethod(),
                entry.getPath(),
                entry.getStatusCode(),
                entry.getDurationMs(),
                entry.isSuccess(),
                entry.getErrorMessage(),
                LocalDateTime.now().toString());

        try {
            rabbitTemplate.convertAndSend(
                    LogExchanges.LOG_EXCHANGE, LogExchanges.OPERATION_LOG_ROUTING_KEY, message);
        } catch (AmqpException e) {
            log.warn("操作ログのキュー発行に失敗したため、同期DB書き込みへフォールバックします", e);
            repository.save(entry);
        }
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
