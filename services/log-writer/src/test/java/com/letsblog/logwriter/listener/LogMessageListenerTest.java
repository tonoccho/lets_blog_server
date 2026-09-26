package com.letsblog.logwriter.listener;

import com.letsblog.logwriter.domain.AuditLog;
import com.letsblog.logwriter.domain.FrontendErrorLog;
import com.letsblog.logwriter.domain.OperationLog;
import com.letsblog.common.messaging.AuditLogMessage;
import com.letsblog.common.messaging.ErrorLogMessage;
import com.letsblog.common.messaging.OperationLogMessage;
import com.letsblog.logwriter.repository.AuditLogRepository;
import com.letsblog.logwriter.repository.FrontendErrorLogRepository;
import com.letsblog.logwriter.repository.OperationLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LogMessageListenerTest {

    @Mock
    private FrontendErrorLogRepository frontendErrorLogRepository;

    @Mock
    private OperationLogRepository operationLogRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    private LogMessageListener listener() {
        return new LogMessageListener(frontendErrorLogRepository, operationLogRepository, auditLogRepository);
    }

    @Test
    void onErrorLog_メッセージの内容をそのままエンティティへ保存する() {
        LocalDateTime now = LocalDateTime.now();
        ErrorLogMessage message = new ErrorLogMessage(
                "boom", "stack-trace", "component-stack", "ERROR", 5L, "keycloak-sub-1", "{}",
                "https://example.com", "agent", now.toString(), now.toString());

        listener().onErrorLog(message);

        ArgumentCaptor<FrontendErrorLog> captor = ArgumentCaptor.forClass(FrontendErrorLog.class);
        verify(frontendErrorLogRepository).save(captor.capture());
        FrontendErrorLog saved = captor.getValue();
        assertEquals("boom", saved.getMessage());
        assertEquals("ERROR", saved.getLevel());
        assertEquals("https://example.com", saved.getUrl());
        assertEquals(5L, saved.getUserId());
        assertEquals("keycloak-sub-1", saved.getActorKeycloakSub());
        assertEquals(now, saved.getTimestamp());
        assertEquals(now, saved.getCreatedAt());
    }

    @Test
    void onOperationLog_メッセージの内容をそのままエンティティへ保存する() {
        LocalDateTime now = LocalDateTime.now();
        OperationLogMessage message = new OperationLogMessage(
                "op-1", 1L, "keycloak-sub-1", "GET", "/api/sites", 200, 42L, true, null, now.toString());

        listener().onOperationLog(message);

        ArgumentCaptor<OperationLog> captor = ArgumentCaptor.forClass(OperationLog.class);
        verify(operationLogRepository).save(captor.capture());
        OperationLog saved = captor.getValue();
        assertEquals("op-1", saved.getOperationId());
        assertEquals(1L, saved.getUserId());
        assertEquals("keycloak-sub-1", saved.getActorKeycloakSub());
        assertEquals("GET", saved.getMethod());
        assertEquals(42L, saved.getDurationMs());
        assertEquals(now, saved.getCreatedAt());
    }

    @Test
    void onAuditLog_メッセージの内容をそのままエンティティへ保存する() {
        LocalDateTime now = LocalDateTime.now();
        AuditLogMessage message = new AuditLogMessage(
                1L, "keycloak-sub-1", "USER_CREATED", "USER", 2L, "{}", "127.0.0.1", "agent", now.toString());

        listener().onAuditLog(message);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertEquals(1L, saved.getUserId());
        assertEquals("keycloak-sub-1", saved.getActorKeycloakSub());
        assertEquals("USER_CREATED", saved.getAction());
        assertEquals("USER", saved.getResourceType());
        assertEquals(2L, saved.getResourceId());
        assertEquals(now, saved.getCreatedAt());
    }

    @Test
    void onAuditLog_上限を超えるchangesはUTF8で65535バイト以内に切り詰めて保存する() {
        String big = "a" + "あ".repeat(30_000);
        AuditLogMessage message = new AuditLogMessage(
                1L, "keycloak-sub-1", "DB_BACKUP_DOWNLOADED", "DATABASE", null, big, "127.0.0.1", "agent",
                LocalDateTime.now().toString());

        listener().onAuditLog(message);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        String saved = captor.getValue().getChanges();
        int bytes = saved.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertTrue(bytes <= 65_535, "bytes=" + bytes);
        assertTrue(bytes > 65_535 - 3, "must keep as much as fits, bytes=" + bytes);
        assertTrue(big.startsWith(saved));
        assertFalse(saved.contains("\uFFFD"));
    }

    @Test
    void onAuditLog_ちょうど上限のchangesはそのまま保存する() {
        String exact = "x".repeat(65_535);
        AuditLogMessage message = new AuditLogMessage(
                1L, "s", "USER_CREATED", "USER", 2L, exact, null, null, LocalDateTime.now().toString());

        listener().onAuditLog(message);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertEquals(exact, captor.getValue().getChanges());
    }

    @Test
    void onAuditLog_changesがnullでも保存できる() {
        AuditLogMessage message = new AuditLogMessage(
                1L, "s", "USER_CREATED", "USER", 2L, null, null, null, LocalDateTime.now().toString());

        listener().onAuditLog(message);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertNull(captor.getValue().getChanges());
    }

    @Test
    void onAuditLog_サロゲートペアの途中で切らない() {
        String big = "x".repeat(65_533) + "\uD83D\uDE00\uD83D\uDE00";
        AuditLogMessage message = new AuditLogMessage(
                1L, "s", "USER_CREATED", "USER", 2L, big, null, null, LocalDateTime.now().toString());

        listener().onAuditLog(message);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertEquals("x".repeat(65_533), captor.getValue().getChanges());
    }
}
