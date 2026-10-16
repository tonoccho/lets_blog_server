package com.letsblog.identity.service;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.letsblog.common.messaging.AuditLogMessage;
import com.letsblog.common.messaging.LogExchanges;
import com.letsblog.identity.dto.ProjectUserSyncSiteResult;
import com.letsblog.identity.testsupport.IdentityWorld;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * issue #1137: ユーザーの無効化・再有効化・削除・role変更が監査ログを記録することの単体テスト。
 * #583の判断(identity-serviceにAOPを持ち込まず明示呼び出しにする)を維持したまま、
 * 記録対象を{@code project_users}以外(users)へ広げたことを検証する。
 * <p>併せて、issue #1242要件5: メンバー個別のユーザー情報同期の実行結果が監査ログに記録されることも検証する。
 *
 * <p>{@code CurrentActorService}・{@code ObjectMapper}は実物。MQへの送信({@code RabbitTemplate})だけが
 * 外部との通信としてMockitoで差し替わり、送った{@link AuditLogMessage}を調べる。
 */
class AuditLogServiceTest {

    private IdentityWorld world;

    @BeforeEach
    void setUp() {
        world = new IdentityWorld();
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private AuditLogMessage onlyMessage() {
        List<AuditLogMessage> messages = world.auditMessages();
        assertEquals(1, messages.size());
        return messages.get(0);
    }

    @Test
    void logUserDeactivated_USERリソースとして操作者の情報つきで発行する() {
        world.actAs(1L, "admin");
        world.request.addHeader("X-Forwarded-For", "203.0.113.9");
        world.request.addHeader("User-Agent", "agent");

        world.auditLogService.logUserDeactivated(42L);

        verify(world.rabbitTemplate).convertAndSend(
                org.mockito.ArgumentMatchers.eq(LogExchanges.LOG_EXCHANGE),
                org.mockito.ArgumentMatchers.eq(LogExchanges.AUDIT_LOG_ROUTING_KEY),
                any(AuditLogMessage.class));
        AuditLogMessage message = onlyMessage();
        assertEquals("USER_DEACTIVATED", message.action());
        assertEquals("USER", message.resourceType());
        assertEquals(42L, message.resourceId());
        assertEquals(1L, message.userId());
        assertEquals("actor-sub-actor1@example.com", message.actorKeycloakSub());
        assertEquals("203.0.113.9", message.remoteIp());
        assertEquals("agent", message.userAgent());
        assertNull(message.changes());
    }

    @Test
    void 操作者がいなくても記録する() {
        world.auditLogService.logUserDeactivated(42L);

        AuditLogMessage message = onlyMessage();
        assertNull(message.userId());
        assertNull(message.actorKeycloakSub());
    }

    @Test
    void logUserReactivated_USER_REACTIVATEDを発行する() {
        world.auditLogService.logUserReactivated(42L);

        assertEquals("USER_REACTIVATED", onlyMessage().action());
    }

    @Test
    void logUserDeleted_USER_DELETEDを発行する() {
        world.auditLogService.logUserDeleted(42L);

        assertEquals("USER_DELETED", onlyMessage().action());
    }

    @Test
    void logUserRoleUpdated_changesに変更前後のroleが入る() {
        world.auditLogService.logUserRoleUpdated(42L, "user", "admin");

        AuditLogMessage message = onlyMessage();
        assertEquals("USER_ROLE_UPDATED", message.action());
        assertTrue(message.changes().contains("user"), "changesに変更前のroleが含まれていません: " + message.changes());
        assertTrue(message.changes().contains("admin"), "changesに変更後のroleが含まれていません: " + message.changes());
    }

    /** 実物の{@link ObjectMapper}に、必ず失敗するシリアライザを登録して「JSON化の失敗」を作る。 */
    private ObjectMapper failingMapper(Class<?> failing) {
        SimpleModule module = new SimpleModule();
        addFailing(module, failing);
        return new ObjectMapper().registerModule(module);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void addFailing(SimpleModule module, Class failing) {
        module.addSerializer(failing, new StdSerializer<Object>(Object.class) {
            @Override
            public void serialize(Object value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                throw JsonMappingException.from(gen, "json化失敗");
            }
        });
    }

    @Test
    void logUserRoleUpdated_JSON生成に失敗してもRabbitMQへの発行自体は継続する() {
        AuditLogService service =
                new AuditLogService(world.rabbitTemplate, world.currentActorService, failingMapper(Map.class));

        assertDoesNotThrow(() -> service.logUserRoleUpdated(42L, "user", "admin"));

        AuditLogMessage message = onlyMessage();
        assertEquals("USER_ROLE_UPDATED", message.action());
        assertNull(message.changes());
    }

    @Test
    void logUserDeactivated_発行に失敗しても例外を投げない() {
        doThrow(new AmqpException("接続できません"))
                .when(world.rabbitTemplate).convertAndSend(anyString(), anyString(), any(AuditLogMessage.class));

        assertDoesNotThrow(() -> world.auditLogService.logUserDeactivated(42L));
    }

    @Test
    void logProjectUserAction_既存の項目でメッセージを発行する_回帰() {
        world.auditLogService.logProjectUserAction(AuditLogService.ACTION_PROJECT_USER_ADDED, 7L);

        AuditLogMessage message = onlyMessage();
        assertEquals("PROJECT_USER_ADDED", message.action());
        assertEquals("PROJECT_USER", message.resourceType());
        assertEquals(7L, message.resourceId());
    }

    @Test
    void logProjectUserAction_発行に失敗しても例外を投げない_回帰() {
        doThrow(new AmqpException("接続できません"))
                .when(world.rabbitTemplate).convertAndSend(anyString(), anyString(), any(AuditLogMessage.class));

        assertDoesNotThrow(() -> world.auditLogService.logProjectUserAction(AuditLogService.ACTION_PROJECT_USER_ADDED, 7L));
    }

    // ------------------------------------------------- logProjectUserSyncAction(issue #1242)

    private List<ProjectUserSyncSiteResult> syncResults() {
        return List.of(
                new ProjectUserSyncSiteResult(30L, "site-30", "本番", true, null),
                new ProjectUserSyncSiteResult(31L, "site-31", "テスト", false, "接続できません"));
    }

    @Test
    void logProjectUserSyncAction_対象メンバーとサイトごとの成否をchangesに入れて発行する() {
        world.auditLogService.logProjectUserSyncAction(7L, 11L, syncResults());

        AuditLogMessage message = onlyMessage();
        assertEquals("PROJECT_USER_SYNCED", message.action());
        assertEquals("PROJECT_USER", message.resourceType());
        assertEquals(7L, message.resourceId());
        assertTrue(message.changes().contains("\"userId\":11"), message.changes());
        assertTrue(message.changes().contains("site-31"), message.changes());
        assertTrue(message.changes().contains("接続できません"), message.changes());
    }

    @Test
    void logProjectUserSyncAction_JSON化に失敗しても例外を投げず発行もしない() {
        AuditLogService service = new AuditLogService(
                world.rabbitTemplate, world.currentActorService, failingMapper(ProjectUserSyncSiteResult.class));

        assertDoesNotThrow(() -> service.logProjectUserSyncAction(7L, 11L, syncResults()));

        assertTrue(world.auditMessages().isEmpty());
    }

    @Test
    void logProjectUserSyncAction_発行に失敗しても例外を投げない() {
        doThrow(new AmqpException("接続できません"))
                .when(world.rabbitTemplate).convertAndSend(anyString(), anyString(), any(AuditLogMessage.class));

        assertDoesNotThrow(() -> world.auditLogService.logProjectUserSyncAction(7L, 11L, syncResults()));
    }
}
