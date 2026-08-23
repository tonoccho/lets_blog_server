package com.letsblog.logwriter.service;

import com.letsblog.common.messaging.ErrorLogMessage;
import com.letsblog.common.messaging.LogExchanges;
import com.letsblog.logwriter.domain.FrontendErrorLog;
import com.letsblog.logwriter.repository.FrontendErrorLogRepository;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * フロントエンドエラーログの記録・読み取り(#572でlegacy-apiから移設)。書き込み経路の設計は
 * {@link OperationLogService}と同じ(OperationLogServiceのJavadoc参照)。
 *
 * <p>actorの解決には{@link CurrentActorService#tryGetCurrentActorId()}を使い、
 * identity-serviceが落ちていてもエラーログの記録自体は失わせない(userIdがnullになるのみ)。
 * actorKeycloakSubはJWTから直接取れるため、identity-service障害の影響を受けない。
 */
@Service
@Transactional
@Slf4j
public class FrontendErrorLogService {

    private final FrontendErrorLogRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final CurrentActorService currentActorService;

    public FrontendErrorLogService(FrontendErrorLogRepository repository, RabbitTemplate rabbitTemplate,
                                    CurrentActorService currentActorService) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.currentActorService = currentActorService;
    }

    public void logError(FrontendErrorLog errorLog) {
        if (errorLog.getCreatedAt() == null) {
            errorLog.setCreatedAt(LocalDateTime.now());
        }
        errorLog.setUserId(currentActorService.tryGetCurrentActorId());
        errorLog.setActorKeycloakSub(currentActorService.getCurrentActorKeycloakSub());

        ErrorLogMessage message = new ErrorLogMessage(
                errorLog.getMessage(),
                errorLog.getStack(),
                errorLog.getComponentStack(),
                errorLog.getLevel(),
                errorLog.getUserId(),
                errorLog.getActorKeycloakSub(),
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
    public Page<FrontendErrorLog> findByLevel(String level, Pageable pageable) {
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
