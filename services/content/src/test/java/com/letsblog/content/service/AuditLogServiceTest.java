package com.letsblog.content.service;

import com.letsblog.common.messaging.AuditLogMessage;
import com.letsblog.common.messaging.LogExchanges;
import com.letsblog.content.domain.AuditLogAction;
import com.letsblog.content.testsupport.ContentWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/** AuditLogService(issue #1821)。MQ(RabbitTemplate)だけが差し替えで、サービスは実物。 */
class AuditLogServiceTest {

    private ContentWorld world;

    @BeforeEach
    void setUp() {
        world = new ContentWorld();
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void 監査ログをログ用の交換機へ発行する() {
        world.auditLogService.log(7L, "kc-sub", AuditLogAction.CUSTOM_TAG_CREATED, "CUSTOM_TAG", 3L, "{}",
                "203.0.113.5", "JUnit");

        ArgumentCaptor<Object> message = ArgumentCaptor.forClass(Object.class);
        verify(world.rabbitTemplate).convertAndSend(
                eq(LogExchanges.LOG_EXCHANGE), eq(LogExchanges.AUDIT_LOG_ROUTING_KEY), message.capture());
        AuditLogMessage sent = (AuditLogMessage) message.getValue();
        assertEquals(7L, sent.userId());
        assertEquals("kc-sub", sent.actorKeycloakSub());
        assertEquals("CUSTOM_TAG_CREATED", sent.action());
        assertEquals("CUSTOM_TAG", sent.resourceType());
        assertEquals(3L, sent.resourceId());
    }

    @Test
    void キューへの発行に失敗しても例外を投げない() {
        doThrow(new AmqpException("down")).when(world.rabbitTemplate)
                .convertAndSend(any(String.class), any(String.class), any(Object.class));

        assertDoesNotThrow(() -> world.auditLogService.log(
                7L, null, AuditLogAction.CUSTOM_TAG_DELETED, "CUSTOM_TAG", 3L, null, null, null));
    }
}
