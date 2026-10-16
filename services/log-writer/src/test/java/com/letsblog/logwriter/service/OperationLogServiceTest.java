package com.letsblog.logwriter.service;

import com.letsblog.common.messaging.LogExchanges;
import com.letsblog.common.messaging.OperationLogMessage;
import com.letsblog.logwriter.domain.OperationLog;
import com.letsblog.logwriter.testsupport.LogWriterIntegrationTestBase;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * OperationLogService(issue #1829で書き直し)。リポジトリは実物(実 MySQL)で、外部との通信は
 * RabbitMQ への発行({@code RabbitTemplate})だけ。区切り削除が複数回に分かれる場合は
 * {@code LogRetentionDeletionIntegrationTest}(区切り件数 5)が通す。
 */
class OperationLogServiceTest extends LogWriterIntegrationTestBase {

    @Autowired
    private OperationLogService service;

    private OperationLog entry() {
        OperationLog entry = new OperationLog();
        entry.setUserId(1L);
        entry.setActorKeycloakSub("keycloak-sub-1");
        entry.setOperationId("op-1");
        entry.setMethod("GET");
        entry.setPath("/api/sites");
        entry.setStatusCode(200);
        entry.setDurationMs(42L);
        entry.setSuccess(true);
        return entry;
    }

    private OperationLogMessage publishedMessage() {
        ArgumentCaptor<OperationLogMessage> captor = ArgumentCaptor.forClass(OperationLogMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(LogExchanges.LOG_EXCHANGE), eq(LogExchanges.OPERATION_LOG_ROUTING_KEY), captor.capture());
        return captor.getValue();
    }

    private void brokerDown() {
        doThrow(new AmqpException("キュー接続エラー"))
                .when(rabbitTemplate).convertAndSend(any(String.class), any(String.class), any(Object.class));
    }

    @Test
    void record_キューへ発行できればDBへは直接書き込まない() {
        service.record(entry());

        OperationLogMessage message = publishedMessage();
        assertEquals(1L, message.userId());
        assertEquals("keycloak-sub-1", message.actorKeycloakSub());
        assertEquals("op-1", message.operationId());
        assertEquals(0, operationLogRepository.count());
    }

    @Test
    void record_キュー発行に失敗したら同期DB書き込みへフォールバックする() {
        brokerDown();

        service.record(entry());

        List<OperationLog> saved = operationLogRepository.findAll();
        assertEquals(1, saved.size());
        assertEquals("op-1", saved.get(0).getOperationId());
        assertEquals(1L, saved.get(0).getUserId());
        assertEquals(42L, saved.get(0).getDurationMs());
    }

    /**
     * フォールバックで保存する行にも{@code created_at}が要る(issue #941 / AT-15)。
     *
     * <p>{@code operation_logs.created_at}は{@code nullable = false}だが、
     * {@link com.letsblog.logwriter.dto.OperationLogRequest#toDomain}はこれを設定しない。
     * 発行できた場合は{@link com.letsblog.logwriter.listener.LogMessageListener}が
     * メッセージの{@code createdAt}から埋めるため成立していたが、フォールバック経路には
     * それが無く、RabbitMQ停止中の{@code POST /api/operation-logs}は
     * {@code DataIntegrityViolationException: Column 'created_at' cannot be null}で
     * 500になっていた。実 MySQL に保存して NOT NULL 制約を通すので、この欠陥を実際に検知できる。
     * 受け入れテストの {@code apps/web/e2e/features/logging/async-path.feature} が経路全体で同じ欠陥を押さえる。
     */
    @Test
    void record_フォールバックで保存する行にも作成日時が入る() {
        brokerDown();
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        service.record(entry());

        LocalDateTime createdAt = operationLogRepository.findAll().get(0).getCreatedAt();
        assertNotNull(createdAt, "created_atがnullのままではDBのNOT NULL制約で保存できない");
        assertFalse(createdAt.isBefore(before), "created_atが記録時刻より前になっている");
        assertFalse(createdAt.isAfter(LocalDateTime.now().plusSeconds(1)), "created_atが未来になっている");
    }

    /** キューへ発行できた場合も、メッセージの{@code createdAt}は空にしない。 */
    @Test
    void record_キューへ発行するメッセージにも作成日時が入る() {
        service.record(entry());

        assertNotNull(publishedMessage().createdAt(), "メッセージのcreatedAtが空だと受信側が現在時刻で代替してしまう");
        assertNotNull(LocalDateTime.parse(publishedMessage().createdAt()));
    }

    @Test
    void findByUser_本人のログだけを新しい順に取得する() {
        LocalDateTime at = LocalDateTime.of(2026, 10, 1, 0, 0);
        saveOperation(1L, "op-old", "GET", "/a", 1L, at);
        saveOperation(1L, "op-new", "GET", "/b", 1L, at.plusMinutes(1));
        saveOperation(2L, "op-other", "GET", "/c", 1L, at);

        List<String> ids = service.findByUser(1L, PageRequest.of(0, 20)).map(OperationLog::getOperationId).getContent();

        assertEquals(List.of("op-new", "op-old"), ids);
    }

    @Test
    void findTrace_operationIdに紐づく本人のログを昇順で取得する() {
        LocalDateTime at = LocalDateTime.of(2026, 10, 1, 0, 0);
        saveOperation(1L, "op-1", "POST", "/second", 1L, at.plusSeconds(1));
        saveOperation(1L, "op-1", "GET", "/first", 1L, at);
        saveOperation(2L, "op-1", "GET", "/other-user", 1L, at);

        List<String> paths = service.findTrace(1L, "op-1").stream().map(OperationLog::getPath).toList();

        assertEquals(List.of("/first", "/second"), paths);
    }

    @Test
    void findTraceAsAdmin_利用者を問わずoperationIdの全行を取得する() {
        LocalDateTime at = LocalDateTime.of(2026, 10, 1, 0, 0);
        saveOperation(1L, "op-1", "GET", "/a", 1L, at);
        saveOperation(2L, "op-1", "GET", "/b", 1L, at.plusSeconds(1));
        saveOperation(2L, "op-2", "GET", "/c", 1L, at);

        assertEquals(2, service.findTraceAsAdmin("op-1").size());
    }

    @Test
    void deleteOldLogs_30日より古い行だけを削除する() {
        LocalDateTime now = LocalDateTime.now();
        saveOperation(1L, "old-1", "GET", "/a", 1L, now.minusDays(31));
        saveOperation(1L, "old-2", "GET", "/a", 1L, now.minusDays(400));
        saveOperation(1L, "new-1", "GET", "/a", 1L, now.minusDays(29));

        service.deleteOldLogs();

        assertEquals(List.of("new-1"), operationLogRepository.findAll().stream().map(OperationLog::getOperationId).toList());
    }

    @Test
    void deleteOldLogs_対象が無ければ何も消さない() {
        saveOperation(1L, "new-1", "GET", "/a", 1L, LocalDateTime.now());

        service.deleteOldLogs();

        assertEquals(1, operationLogRepository.count());
    }

    @Test
    void deleteOldLogs_毎日UTC午前3時にスケジュール実行される() throws NoSuchMethodException {
        Method method = OperationLogService.class.getMethod("deleteOldLogs");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertEquals("0 0 3 * * *", scheduled.cron());
        assertEquals("UTC", scheduled.zone());
    }
}
