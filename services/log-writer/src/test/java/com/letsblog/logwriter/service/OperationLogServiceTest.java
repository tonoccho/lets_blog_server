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
import static org.mockito.ArgumentMatchers.any;
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
    void deleteOldLogs_30日以上前のログのみ削除する() {
        service = new OperationLogService(repository, rabbitTemplate);
        OperationLog oldLog = new OperationLog();
        when(repository.findByCreatedAtBefore(any(LocalDateTime.class))).thenReturn(List.of(oldLog));

        service.deleteOldLogs();

        verify(repository, times(1)).deleteAll(List.of(oldLog));
    }

    @Test
    void deleteOldLogs_対象が無ければ削除処理を呼ばない() {
        service = new OperationLogService(repository, rabbitTemplate);
        when(repository.findByCreatedAtBefore(any(LocalDateTime.class))).thenReturn(List.of());

        service.deleteOldLogs();

        verify(repository, times(0)).deleteAll(any());
    }

    @Test
    void deleteOldLogs_毎日UTC午前3時にスケジュール実行される() throws NoSuchMethodException {
        Method method = OperationLogService.class.getMethod("deleteOldLogs");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertEquals("0 0 3 * * *", scheduled.cron());
        assertEquals("UTC", scheduled.zone());
    }
}
