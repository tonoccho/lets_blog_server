package com.letsblog.api.service;

import com.letsblog.common.messaging.LogExchanges;
import com.letsblog.api.domain.AuditLog;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.common.messaging.AuditLogMessage;
import com.letsblog.api.repository.AuditLogRepository;
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
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private AuditLogService service;

    @Test
    void log_キューへ発行できればDBへは直接書き込まない() {
        service = new AuditLogService(auditLogRepository, rabbitTemplate);

        service.log(1L, AuditLogAction.USER_CREATED, "USER", 2L, "{}", "127.0.0.1", "test-agent");

        ArgumentCaptor<AuditLogMessage> captor = ArgumentCaptor.forClass(AuditLogMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(LogExchanges.LOG_EXCHANGE), eq(LogExchanges.AUDIT_LOG_ROUTING_KEY), captor.capture());
        AuditLogMessage message = captor.getValue();
        assertEquals(1L, message.userId());
        assertEquals(AuditLogAction.USER_CREATED.name(), message.action());
        assertEquals("USER", message.resourceType());
        assertEquals(2L, message.resourceId());
        assertEquals("127.0.0.1", message.remoteIp());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void log_キュー発行に失敗したら同期DB書き込みへフォールバックする() {
        service = new AuditLogService(auditLogRepository, rabbitTemplate);
        doThrow(new AmqpException("キュー接続エラー"))
                .when(rabbitTemplate).convertAndSend(any(String.class), any(String.class), any(Object.class));

        service.log(1L, AuditLogAction.USER_CREATED, "USER", 2L, "{}", "127.0.0.1", "test-agent");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(1)).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertEquals(1L, saved.getUserId());
        assertEquals(AuditLogAction.USER_CREATED, saved.getAction());
        assertEquals("USER", saved.getResourceType());
        assertEquals(2L, saved.getResourceId());
        assertEquals("127.0.0.1", saved.getRemoteIp());
    }

    @Test
    void deleteOldLogs_1年以上前のログのみ削除する() {
        service = new AuditLogService(auditLogRepository, rabbitTemplate);
        AuditLog oldLog = new AuditLog();
        when(auditLogRepository.findByCreatedAtBefore(any(LocalDateTime.class)))
                .thenReturn(List.of(oldLog));

        service.deleteOldLogs();

        verify(auditLogRepository, times(1)).deleteAll(List.of(oldLog));
    }

    @Test
    void deleteOldLogs_対象が無ければ削除処理を呼ばない() {
        service = new AuditLogService(auditLogRepository, rabbitTemplate);
        when(auditLogRepository.findByCreatedAtBefore(any(LocalDateTime.class)))
                .thenReturn(List.of());

        service.deleteOldLogs();

        verify(auditLogRepository, times(0)).deleteAll(any());
    }

    @Test
    void deleteOldLogs_毎日UTC午前2時にスケジュール実行される() throws NoSuchMethodException {
        Method method = AuditLogService.class.getMethod("deleteOldLogs");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertEquals("0 0 2 * * *", scheduled.cron());
        assertEquals("UTC", scheduled.zone());
    }
}
