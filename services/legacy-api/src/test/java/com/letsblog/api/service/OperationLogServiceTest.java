package com.letsblog.api.service;

import com.letsblog.common.messaging.LogExchanges;
import com.letsblog.api.domain.OperationLog;
import com.letsblog.common.messaging.OperationLogMessage;
import com.letsblog.api.repository.OperationLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

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

    @Test
    void record_キューへ発行できればDBへは直接書き込まない() {
        service = new OperationLogService(repository, rabbitTemplate);
        OperationLog entry = new OperationLog();
        entry.setUserId(1L);
        entry.setOperationId("op-1");
        entry.setMethod("GET");
        entry.setPath("/api/sites");
        entry.setStatusCode(200);
        entry.setDurationMs(42L);
        entry.setSuccess(true);

        service.record(entry);

        ArgumentCaptor<OperationLogMessage> captor = ArgumentCaptor.forClass(OperationLogMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(LogExchanges.LOG_EXCHANGE), eq(LogExchanges.OPERATION_LOG_ROUTING_KEY), captor.capture());
        OperationLogMessage message = captor.getValue();
        assertEquals(1L, message.userId());
        assertEquals("op-1", message.operationId());
        assertEquals("GET", message.method());
        assertEquals("/api/sites", message.path());
        assertEquals(200, message.statusCode());
        assertEquals(42L, message.durationMs());
        verify(repository, never()).save(any());
    }

    @Test
    void record_キュー発行に失敗したら同期DB書き込みへフォールバックする() {
        service = new OperationLogService(repository, rabbitTemplate);
        doThrow(new AmqpException("キュー接続エラー"))
                .when(rabbitTemplate).convertAndSend(any(String.class), any(String.class), any(Object.class));
        OperationLog entry = new OperationLog();
        entry.setUserId(1L);
        entry.setOperationId("op-1");
        entry.setMethod("GET");
        entry.setPath("/api/sites");
        entry.setStatusCode(200);
        entry.setDurationMs(42L);
        entry.setSuccess(true);

        service.record(entry);

        ArgumentCaptor<OperationLog> captor = ArgumentCaptor.forClass(OperationLog.class);
        verify(repository, times(1)).save(captor.capture());
        OperationLog saved = captor.getValue();
        assertEquals(1L, saved.getUserId());
        assertEquals("op-1", saved.getOperationId());
        assertEquals("GET", saved.getMethod());
        assertEquals("/api/sites", saved.getPath());
        assertEquals(200, saved.getStatusCode());
        assertEquals(42L, saved.getDurationMs());
    }

    @Test
    void deleteOldLogs_30日以上前のログのみ削除する() {
        service = new OperationLogService(repository, rabbitTemplate);
        OperationLog oldLog = new OperationLog();
        when(repository.findByCreatedAtBefore(any(LocalDateTime.class)))
                .thenReturn(List.of(oldLog));

        service.deleteOldLogs();

        verify(repository, times(1)).deleteAll(List.of(oldLog));
    }

    @Test
    void deleteOldLogs_対象が無ければ削除処理を呼ばない() {
        service = new OperationLogService(repository, rabbitTemplate);
        when(repository.findByCreatedAtBefore(any(LocalDateTime.class)))
                .thenReturn(List.of());

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
