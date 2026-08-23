package com.letsblog.api.service;

import com.letsblog.common.messaging.LogExchanges;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.common.messaging.AuditLogMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * #572で読み取り・保持期間管理をlog-writerへ移設したため、このテストは記録(書き込み)経路のみを
 * 検証する(移設した読み取り・保持期間管理のテストはservices/log-writer側へ移設済み)。
 */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    private AuditLogService service;

    @Test
    void log_キューへ発行できる() {
        service = new AuditLogService(rabbitTemplate);

        service.log(1L, "keycloak-sub-1", AuditLogAction.USER_CREATED, "USER", 2L, "{}", "127.0.0.1", "test-agent");

        ArgumentCaptor<AuditLogMessage> captor = ArgumentCaptor.forClass(AuditLogMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(LogExchanges.LOG_EXCHANGE), eq(LogExchanges.AUDIT_LOG_ROUTING_KEY), captor.capture());
        AuditLogMessage message = captor.getValue();
        assertEquals(1L, message.userId());
        assertEquals("keycloak-sub-1", message.actorKeycloakSub());
        assertEquals(AuditLogAction.USER_CREATED.name(), message.action());
        assertEquals("USER", message.resourceType());
        assertEquals(2L, message.resourceId());
        assertEquals("127.0.0.1", message.remoteIp());
    }

    @Test
    void log_キュー発行に失敗しても例外を投げずログへ記録するだけに留める() {
        service = new AuditLogService(rabbitTemplate);
        doThrow(new AmqpException("キュー接続エラー"))
                .when(rabbitTemplate).convertAndSend(any(String.class), any(String.class), any(Object.class));

        assertDoesNotThrow(() -> service.log(
                1L, "keycloak-sub-1", AuditLogAction.USER_CREATED, "USER", 2L, "{}", "127.0.0.1", "test-agent"));
    }
}
