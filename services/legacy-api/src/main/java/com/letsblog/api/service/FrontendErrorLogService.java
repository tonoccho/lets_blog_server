package com.letsblog.api.service;

import com.letsblog.common.messaging.LogExchanges;
import com.letsblog.api.domain.FrontendErrorLog;
import com.letsblog.common.messaging.ErrorLogMessage;
import com.letsblog.api.repository.FrontendErrorLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@Transactional
@Slf4j
public class FrontendErrorLogService {

    private final FrontendErrorLogRepository repository;
    private final RabbitTemplate rabbitTemplate;

    public FrontendErrorLogService(FrontendErrorLogRepository repository, RabbitTemplate rabbitTemplate) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * ログメッセージキューイング(issue #466)。キューへの発行を優先し、記録自体はlog-writer
     * サービスに委譲する。発行に失敗した場合のみ、ログ欠落を防ぐためこのAPIサーバー自身が
     * 従来通り同期的にDBへ書き込む。
     */
    public void logError(FrontendErrorLog errorLog) {
        if (errorLog.getCreatedAt() == null) {
            errorLog.setCreatedAt(LocalDateTime.now());
        }

        ErrorLogMessage message = new ErrorLogMessage(
                errorLog.getMessage(),
                errorLog.getStack(),
                errorLog.getComponentStack(),
                errorLog.getLevel() != null ? errorLog.getLevel().name() : null,
                errorLog.getContext(),
                errorLog.getUrl(),
                errorLog.getUserAgent(),
                errorLog.getTimestamp() != null ? errorLog.getTimestamp().toString() : null,
                errorLog.getCreatedAt().toString());

        try {
            rabbitTemplate.convertAndSend(
                    LogExchanges.LOG_EXCHANGE, LogExchanges.ERROR_LOG_ROUTING_KEY, message);
        } catch (AmqpException e) {
            log.warn("フロントエンドエラーログのキュー発行に失敗したため、同期DB書き込みへフォールバックします", e);
            repository.save(errorLog);
        }
    }

    @Transactional(readOnly = true)
    public Page<FrontendErrorLog> findAll(Pageable pageable) {
        return repository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Page<FrontendErrorLog> findByLevel(FrontendErrorLog.ErrorLevel level, Pageable pageable) {
        return repository.findByLevel(level, pageable);
    }

    @Transactional(readOnly = true)
    public Page<FrontendErrorLog> findByDateRange(LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        return repository.findByCreatedAtBetween(startDate, endDate, pageable);
    }

    @Transactional(readOnly = true)
    public Page<FrontendErrorLog> findByUrl(String url, Pageable pageable) {
        return repository.findByUrl(url, pageable);
    }
}
