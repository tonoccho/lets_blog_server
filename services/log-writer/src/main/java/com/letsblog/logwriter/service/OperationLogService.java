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
import org.springframework.beans.factory.annotation.Value;
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

    static final int DEFAULT_DELETE_BATCH_SIZE = 10_000;

    private final OperationLogRepository repository;
    private final RabbitTemplate rabbitTemplate;

    /** 1回の削除で消す最大件数(issue #1727)。溜まった件数が多くても1トランザクションを長くしない。 */
    @Value("${log.retention.delete-batch-size:" + DEFAULT_DELETE_BATCH_SIZE + "}")
    private int deleteBatchSize = DEFAULT_DELETE_BATCH_SIZE;

    public OperationLogService(OperationLogRepository repository, RabbitTemplate rabbitTemplate) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Transactional
    public void record(OperationLog entry) {
        // 記録時刻はキュー経路とフォールバック経路で同じ値を使う。どちらを通ったかで
        // 時刻の意味が変わってはいけない。
        LocalDateTime recordedAt = LocalDateTime.now();

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
                recordedAt.toString());

        try {
            rabbitTemplate.convertAndSend(
                    LogExchanges.LOG_EXCHANGE, LogExchanges.OPERATION_LOG_ROUTING_KEY, message);
        } catch (AmqpException e) {
            log.warn("操作ログのキュー発行に失敗したため、同期DB書き込みへフォールバックします", e);
            // createdAtはここで入れる(issue #941)。呼び出し元が渡すOperationLogは
            // OperationLogRequest#toDomainが組み立てたもので、createdAtを持たない。
            // キュー経由ならLogMessageListenerがメッセージのcreatedAtから埋めるため
            // 成立していたが、フォールバック経路には埋める者が居らず、
            // operation_logs.created_at(NOT NULL)へnullを挿そうとして
            // DataIntegrityViolationExceptionになっていた。RabbitMQ停止中の
            // POST /api/operation-logs が500になり、ログを残すための経路が
            // ログを落としていた。しかもWebのBFF(apps/web/src/lib/apiClient.tsの
            // recordOperationLog)は記録の失敗を握り潰すので、誰も気付かない。
            entry.setCreatedAt(recordedAt);
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

    /** 管理者向け: 利用者を問わずoperationIdの全行を返す(issue #1471)。 */
    @Transactional(readOnly = true)
    public List<OperationLog> findTraceAsAdmin(String operationId) {
        return repository.findByOperationIdOrderByCreatedAtAsc(operationId);
    }

    /**
     * 30日以上前のログを削除する。毎日UTC 03:00に自動実行する(監査ログの削除時刻とずらし、負荷を分散する)。
     */
    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    public void deleteOldLogs() {
        LocalDateTime threshold = LocalDateTime.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
        long total = 0;
        int deleted;
        do {
            deleted = repository.deleteBatchBefore(threshold, deleteBatchSize);
            total += deleted;
        } while (deleted >= deleteBatchSize);
        if (total > 0) {
            log.info("Deleted {} old operation logs before {}", total, threshold);
        }
    }
}
