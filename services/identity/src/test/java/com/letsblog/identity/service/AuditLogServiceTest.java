package com.letsblog.identity.service;

import com.letsblog.common.messaging.AuditLogMessage;
import com.letsblog.common.messaging.LogExchanges;
import com.letsblog.identity.dto.ProjectUserSyncSiteResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * issue #1242要件5: メンバー個別のユーザー情報同期の実行結果(対象メンバー、対象プロジェクト、
 * 成功/失敗した環境)が監査ログに記録されることの単体検証。
 */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private CurrentActorService currentActorService;

    private AuditLogService service() {
        return new AuditLogService(rabbitTemplate, currentActorService, new ObjectMapper());
    }

    @Test
    void 同期結果を成功失敗ともに含めて監査ログを送信する() {
        when(currentActorService.getCurrentActorId()).thenReturn(1L);
        when(currentActorService.getCurrentActorKeycloakSub()).thenReturn("sub-1");
        when(currentActorService.getRemoteIp()).thenReturn("127.0.0.1");
        when(currentActorService.getUserAgent()).thenReturn("jest");

        List<ProjectUserSyncSiteResult> results = List.of(
                new ProjectUserSyncSiteResult(10L, "ok-key", "OK", true, null),
                new ProjectUserSyncSiteResult(20L, "ng-key", "NG", false, "接続に失敗しました"));

        service().logProjectUserSyncAction(5L, 42L, results);

        ArgumentCaptor<AuditLogMessage> captor = ArgumentCaptor.forClass(AuditLogMessage.class);
        verify(rabbitTemplate).convertAndSend(eq(LogExchanges.LOG_EXCHANGE), eq(LogExchanges.AUDIT_LOG_ROUTING_KEY), captor.capture());

        AuditLogMessage message = captor.getValue();
        assertTrue(message.action().equals(AuditLogService.ACTION_PROJECT_USER_SYNCED));
        assertTrue(message.resourceId().equals(5L));
        assertTrue(message.changes().contains("42"));
        assertTrue(message.changes().contains("ok-key"));
        assertTrue(message.changes().contains("ng-key"));
        assertTrue(message.changes().contains("接続に失敗しました"));
    }

    @Test
    void 送信に失敗しても例外を外へ伝播しない() {
        doThrow(new AmqpException("down") {
        }).when(rabbitTemplate).convertAndSend(any(String.class), any(String.class), any(Object.class));

        assertDoesNotThrow(() -> service().logProjectUserSyncAction(5L, 42L, List.of()));
    }
}
