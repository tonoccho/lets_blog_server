package com.letsblog.logwriter.service;

import com.letsblog.common.messaging.LogExchanges;
import com.letsblog.common.messaging.OperationLogMessage;
import com.letsblog.logwriter.domain.OperationLog;
import com.letsblog.logwriter.repository.OperationLogRepository;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperationLogServiceTest {

    @Mock
    private OperationLogRepository repository;

    @Mock
    private RabbitTemplate rabbitTemplate;

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

    @Test
    void record_キューへ発行できればDBへは直接書き込まない() {
        service = new OperationLogService(repository, rabbitTemplate);

        service.record(entry());

        ArgumentCaptor<OperationLogMessage> captor = ArgumentCaptor.forClass(OperationLogMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(LogExchanges.LOG_EXCHANGE), eq(LogExchanges.OPERATION_LOG_ROUTING_KEY), captor.capture());
        OperationLogMessage message = captor.getValue();
        assertEquals(1L, message.userId());
        assertEquals("keycloak-sub-1", message.actorKeycloakSub());
        assertEquals("op-1", message.operationId());
        verify(repository, never()).save(any());
    }

    @Test
    void record_キュー発行に失敗したら同期DB書き込みへフォールバックする() {
        service = new OperationLogService(repository, rabbitTemplate);
        doThrow(new AmqpException("キュー接続エラー"))
                .when(rabbitTemplate).convertAndSend(any(String.class), any(String.class), any(Object.class));

        service.record(entry());

        ArgumentCaptor<OperationLog> captor = ArgumentCaptor.forClass(OperationLog.class);
        verify(repository, times(1)).save(captor.capture());
        assertEquals("op-1", captor.getValue().getOperationId());
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
     * 500になっていた。BFF側({@code apps/web/src/lib/apiClient.ts}の
     * {@code recordOperationLog})は記録の失敗を握り潰すため、
     * <b>ログが落ちていること自体が誰にも見えない</b>。
     *
     * <p>受け入れテストの
     * {@code apps/web/e2e/features/logging/async-path.feature}
     * 「RabbitMQ停止中のログは、定義どおり操作ログとエラーログが残り監査ログが失われる」
     * が同じ欠陥を経路全体で押さえる。
     */
    @Test
    void record_フォールバックで保存する行にも作成日時が入る() {
        service = new OperationLogService(repository, rabbitTemplate);
        doThrow(new AmqpException("キュー接続エラー"))
                .when(rabbitTemplate).convertAndSend(any(String.class), any(String.class), any(Object.class));
        LocalDateTime before = LocalDateTime.now();

        service.record(entry());

        ArgumentCaptor<OperationLog> captor = ArgumentCaptor.forClass(OperationLog.class);
        verify(repository, times(1)).save(captor.capture());
        LocalDateTime createdAt = captor.getValue().getCreatedAt();
        assertNotNull(createdAt, "created_atがnullのままではDBのNOT NULL制約で保存できない");
        assertFalse(createdAt.isBefore(before), "created_atが記録時刻より前になっている");
        assertFalse(createdAt.isAfter(LocalDateTime.now()), "created_atが未来になっている");
    }

    /** キューへ発行できた場合も、メッセージの{@code createdAt}は空にしない。 */
    @Test
    void record_キューへ発行するメッセージにも作成日時が入る() {
        service = new OperationLogService(repository, rabbitTemplate);

        service.record(entry());

        ArgumentCaptor<OperationLogMessage> captor = ArgumentCaptor.forClass(OperationLogMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(LogExchanges.LOG_EXCHANGE), eq(LogExchanges.OPERATION_LOG_ROUTING_KEY), captor.capture());
        assertNotNull(captor.getValue().createdAt(), "メッセージのcreatedAtが空だと受信側が現在時刻で代替してしまう");
        assertNotNull(LocalDateTime.parse(captor.getValue().createdAt()));
    }

    @Test
    void findByUser_本人のログを取得する() {
        service = new OperationLogService(repository, rabbitTemplate);
        when(repository.findByUserIdOrderByCreatedAtDesc(eq(1L), any())).thenReturn(null);

        service.findByUser(1L, org.springframework.data.domain.PageRequest.of(0, 20));

        verify(repository).findByUserIdOrderByCreatedAtDesc(eq(1L), any());
    }

    @Test
    void findTrace_operationIdに紐づくログを取得する() {
        service = new OperationLogService(repository, rabbitTemplate);
        when(repository.findByUserIdAndOperationIdOrderByCreatedAtAsc(1L, "op-1")).thenReturn(List.of(entry()));

        List<OperationLog> result = service.findTrace(1L, "op-1");

        assertEquals(1, result.size());
    }

    @Test
    void findTraceAsAdmin_利用者を問わずoperationIdの全行を取得する() {
        service = new OperationLogService(repository, rabbitTemplate);
        when(repository.findByOperationIdOrderByCreatedAtAsc("op-1")).thenReturn(List.of(entry(), entry()));

        assertEquals(2, service.findTraceAsAdmin("op-1").size());
        verify(repository, never()).findByUserIdAndOperationIdOrderByCreatedAtAsc(any(), any());
    }

    @Test
    void deleteOldLogs_区切り件数ずつ端数の回で止まるまで削除する() {
        service = new OperationLogService(repository, rabbitTemplate);
        int batch = OperationLogService.DEFAULT_DELETE_BATCH_SIZE;
        when(repository.deleteBatchBefore(any(LocalDateTime.class), eq(batch))).thenReturn(batch, batch, 3);

        service.deleteOldLogs();

        verify(repository, times(3)).deleteBatchBefore(any(LocalDateTime.class), eq(batch));
    }

    @Test
    void deleteOldLogs_対象が無ければ1回で止まる() {
        service = new OperationLogService(repository, rabbitTemplate);
        when(repository.deleteBatchBefore(any(LocalDateTime.class), anyInt())).thenReturn(0);

        service.deleteOldLogs();

        verify(repository, times(1)).deleteBatchBefore(any(LocalDateTime.class), anyInt());
    }

    @Test
    void deleteOldLogs_全件をメモリに読み込まない() {
        service = new OperationLogService(repository, rabbitTemplate);
        when(repository.deleteBatchBefore(any(LocalDateTime.class), anyInt())).thenReturn(0);

        service.deleteOldLogs();

        verify(repository, never()).findAll();
    }

    @Test
    void deleteOldLogs_毎日UTC午前3時にスケジュール実行される() throws NoSuchMethodException {
        Method method = OperationLogService.class.getMethod("deleteOldLogs");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertEquals("0 0 3 * * *", scheduled.cron());
        assertEquals("UTC", scheduled.zone());
    }
}
