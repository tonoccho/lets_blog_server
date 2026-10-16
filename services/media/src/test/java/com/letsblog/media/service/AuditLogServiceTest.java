package com.letsblog.media.service;

import com.letsblog.common.messaging.AuditLogMessage;
import com.letsblog.media.testsupport.MediaWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * 監査ログのMQ発行(issue #573 stage3)。MQ(RabbitTemplate)が使えなくても、呼び出し元の処理は失敗させない。
 *
 * <p>issue #1819: {@code AuditLogService}は実物で、MQ(RabbitTemplate)だけをmockが演じる。
 */
@DisplayName("AuditLogService: 監査ログのキュー発行")
class AuditLogServiceTest {

    private final MediaWorld world = new MediaWorld();

    @AfterEach
    void close() {
        world.close();
    }

    @Test
    @DisplayName("操作者・操作・対象・変更内容をキューへ送る")
    void 発行する() {
        world.auditLogService.log(9L, "sub-1", AuditLogService.ACTION_MEDIA_GARBAGE_COLLECTED, "PROJECT", 1L,
                "{\"k\":1}", "203.0.113.9", "curl/8");

        AuditLogMessage message = (AuditLogMessage) world.publishedMessages().get(0);
        assertEquals(9L, message.userId());
        assertEquals("sub-1", message.actorKeycloakSub());
        assertEquals(AuditLogService.ACTION_MEDIA_GARBAGE_COLLECTED, message.action());
        assertEquals("PROJECT", message.resourceType());
        assertEquals(1L, message.resourceId());
        assertEquals("{\"k\":1}", message.changes());
        assertEquals("203.0.113.9", message.remoteIp());
        assertEquals("curl/8", message.userAgent());
    }

    @Test
    @DisplayName("MQへ接続できなくても例外にしない(記録できなかったことはログに残す)")
    void MQが使えなくても失敗させない() {
        doThrow(new AmqpConnectException(new IOException("refused"))).when(world.rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(Object.class));

        assertDoesNotThrow(() -> world.auditLogService.log(
                9L, "sub-1", "ACTION", "PROJECT", 1L, "{}", null, null));
    }
}
