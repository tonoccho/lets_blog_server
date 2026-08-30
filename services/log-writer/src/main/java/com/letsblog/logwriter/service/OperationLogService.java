package com.letsblog.logwriter.service;

import com.letsblog.common.messaging.LogExchanges;
import com.letsblog.common.messaging.OperationLogMessage;
import com.letsblog.logwriter.domain.OperationLog;
import com.letsblog.logwriter.repository.OperationLogRepository;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Web BFF(Next.js)がバックエンドAPIへ行った全リクエストの技術的な操作ログを記録・読み取りする
 * (#572でlegacy-apiから移設)。
 *
 * <p>書き込み経路(issue #466由来)は、legacy-apiが他サービスのコントローラーに分散していた頃と
 * 同じくキューへの発行を優先し、実際の永続化は{@link com.letsblog.logwriter.listener.LogMessageListener}
 * (同一サービス内のRabbitMQコンシューマー)に委ねる。以前は「apiサーバーが発行、log-writerが
 * 消費」という別サービス間の非同期化だったが、#572で両方が同一サービスへ集約された後も、
 * リクエストスレッドを長時間ブロックしないためのfire-and-forget的な利点を維持する目的で
 * あえてこの構成を残す。発行に失敗した場合のみ、ログ欠落を防ぐため同期DB書き込みへ
 * フォールバックする(REQUIRES_NEWではなく通常のトランザクションで良い。呼び出し元は
 * このサービス自身のコントローラーであり、legacy-api時代のような別トランザクション文脈からの
 * 呼び出しではないため)。
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

    @Transactional
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
