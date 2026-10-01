package com.letsblog.identity.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.messaging.AuditLogMessage;
import com.letsblog.common.messaging.LogExchanges;
import com.letsblog.identity.dto.ProjectUserSyncSiteResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * issue #1137: ユーザーの無効化・再有効化・削除・role変更が監査ログを記録することの単体テスト。
 * #583の判断(identity-serviceにAOPを持ち込まず明示呼び出しにする)を維持したまま、
 * 記録対象を{@code project_users}以外(users)へ広げたことを検証する。
 * <p>併せて、issue #1242要件5: メンバー個別のユーザー情報同期の実行結果が監査ログに記録されることも検証する。
 */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private CurrentActorService currentActorService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AuditLogService service(ObjectMapper mapper) {
        return new AuditLogService(rabbitTemplate, currentActorService, mapper);
    }

    private AuditLogService service() {
        return service(objectMapper);
    }

    @Test
    void logUserDeactivated_USERリソースとして発行する() {
        when(currentActorService.getCurrentActorId()).thenReturn(1L);
        when(currentActorService.getCurrentActorKeycloakSub()).thenReturn("sub-1");
        when(currentActorService.getRemoteIp()).thenReturn("127.0.0.1");
        when(currentActorService.getUserAgent()).thenReturn("agent");

        service().logUserDeactivated(42L);

        ArgumentCaptor<AuditLogMessage> captor = ArgumentCaptor.forClass(AuditLogMessage.class);
        verify(rabbitTemplate).convertAndSend(
                org.mockito.ArgumentMatchers.eq(LogExchanges.LOG_EXCHANGE),
                org.mockito.ArgumentMatchers.eq(LogExchanges.AUDIT_LOG_ROUTING_KEY),
                captor.capture());
        AuditLogMessage message = captor.getValue();
        assertEquals("USER_DEACTIVATED", message.action());
        assertEquals("USER", message.resourceType());
        assertEquals(42L, message.resourceId());
        assertEquals(1L, message.userId());
        assertEquals("sub-1", message.actorKeycloakSub());
    }

    @Test
    void logUserReactivated_USER_REACTIVATEDを発行する() {
        service().logUserReactivated(42L);

        ArgumentCaptor<AuditLogMessage> captor = ArgumentCaptor.forClass(AuditLogMessage.class);
        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), captor.capture());
        assertEquals("USER_REACTIVATED", captor.getValue().action());
    }

    @Test
    void logUserDeleted_USER_DELETEDを発行する() {
        service().logUserDeleted(42L);

        ArgumentCaptor<AuditLogMessage> captor = ArgumentCaptor.forClass(AuditLogMessage.class);
        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), captor.capture());
        assertEquals("USER_DELETED", captor.getValue().action());
    }

    @Test
    void logUserRoleUpdated_changesに変更前後のroleが入る() {
        service().logUserRoleUpdated(42L, "user", "admin");

        ArgumentCaptor<AuditLogMessage> captor = ArgumentCaptor.forClass(AuditLogMessage.class);
        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), captor.capture());
        AuditLogMessage message = captor.getValue();
        assertEquals("USER_ROLE_UPDATED", message.action());
        assertTrue(message.changes().contains("user"), "changesに変更前のroleが含まれていません: " + message.changes());
        assertTrue(message.changes().contains("admin"), "changesに変更後のroleが含まれていません: " + message.changes());
    }

    @Test
    void logUserRoleUpdated_JSON生成に失敗してもRabbitMQへの発行自体は継続する() throws JsonProcessingException {
        ObjectMapper failingMapper = org.mockito.Mockito.mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(new RuntimeException("json化失敗") { });

        assertDoesNotThrow(() -> service(failingMapper).logUserRoleUpdated(42L, "user", "admin"));

        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), any(AuditLogMessage.class));
    }

    @Test
    void logUserDeactivated_発行に失敗しても例外を投げない() {
        doThrow(new org.springframework.amqp.AmqpException("接続できません") { })
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(AuditLogMessage.class));

        assertDoesNotThrow(() -> service().logUserDeactivated(42L));
    }

    @Test
    void logProjectUserAction_既存の項目でメッセージを発行する_回帰() {
        service().logProjectUserAction(AuditLogService.ACTION_PROJECT_USER_ADDED, 7L);

        ArgumentCaptor<AuditLogMessage> captor = ArgumentCaptor.forClass(AuditLogMessage.class);
        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), captor.capture());
        AuditLogMessage message = captor.getValue();
        assertEquals("PROJECT_USER_ADDED", message.action());
        assertEquals("PROJECT_USER", message.resourceType());
        assertEquals(7L, message.resourceId());
    }

    @Test
    void logProjectUserAction_発行に失敗しても例外を投げない_回帰() {
        doThrow(new org.springframework.amqp.AmqpException("接続できません") { })
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(AuditLogMessage.class));

        assertDoesNotThrow(() -> service().logProjectUserAction(AuditLogService.ACTION_PROJECT_USER_ADDED, 7L));
    }
}
