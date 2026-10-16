package com.letsblog.logwriter.listener;

import com.letsblog.common.messaging.AuditLogMessage;
import com.letsblog.common.messaging.ErrorLogMessage;
import com.letsblog.common.messaging.OperationLogMessage;
import com.letsblog.logwriter.domain.AuditLog;
import com.letsblog.logwriter.domain.FrontendErrorLog;
import com.letsblog.logwriter.domain.OperationLog;
import com.letsblog.logwriter.testsupport.LogWriterIntegrationTestBase;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LogMessageListener(issue #1829で書き直し)。リポジトリのモックをやめ、メッセージを受けたリスナーが
 * 実 MySQL に行を保存することを確かめる(MQ からの受信そのものはブローカーが要るので、リスナーのメソッドを直接呼ぶ)。
 */
class LogMessageListenerTest extends LogWriterIntegrationTestBase {

    @Autowired
    private LogMessageListener listener;

    private AuditLog onlyAuditLog() {
        assertEquals(1, auditLogRepository.count());
        return auditLogRepository.findAll().get(0);
    }

    private static AuditLogMessage audit(String changes, String remoteIp) {
        return new AuditLogMessage(
                1L, "s", "USER_CREATED", "USER", 2L, changes, remoteIp, null, LocalDateTime.now().toString());
    }

    @Test
    void onErrorLog_メッセージの内容をそのまま保存する() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        ErrorLogMessage message = new ErrorLogMessage(
                "boom", "stack-trace", "component-stack", "ERROR", 5L, "keycloak-sub-1", "{}",
                "https://example.com", "agent", now.toString(), now.toString());

        listener.onErrorLog(message);

        assertEquals(1, frontendErrorLogRepository.count());
        FrontendErrorLog saved = frontendErrorLogRepository.findAll().get(0);
        assertEquals("boom", saved.getMessage());
        assertEquals("stack-trace", saved.getStack());
        assertEquals("component-stack", saved.getComponentStack());
        assertEquals("ERROR", saved.getLevel());
        assertEquals("https://example.com", saved.getUrl());
        assertEquals("agent", saved.getUserAgent());
        assertEquals("{}", saved.getContext());
        assertEquals(5L, saved.getUserId());
        assertEquals("keycloak-sub-1", saved.getActorKeycloakSub());
        assertEquals(now, saved.getTimestamp());
        assertEquals(now, saved.getCreatedAt());
    }

    @Test
    void onErrorLog_時刻が無ければ現在時刻で保存する() {
        LocalDateTime before = LocalDateTime.now().minusSeconds(2);
        ErrorLogMessage message = new ErrorLogMessage(
                "boom", null, null, "WARN", null, null, null, null, null, null, null);

        listener.onErrorLog(message);

        FrontendErrorLog saved = frontendErrorLogRepository.findAll().get(0);
        assertFalse(saved.getTimestamp().isBefore(before));
        assertFalse(saved.getCreatedAt().isBefore(before));
        assertNull(saved.getUserId());
    }

    @Test
    void onOperationLog_メッセージの内容をそのまま保存する() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        OperationLogMessage message = new OperationLogMessage(
                "op-1", 1L, "keycloak-sub-1", "GET", "/api/sites", 200, 42L, true, null, now.toString());

        listener.onOperationLog(message);

        assertEquals(1, operationLogRepository.count());
        OperationLog saved = operationLogRepository.findAll().get(0);
        assertEquals("op-1", saved.getOperationId());
        assertEquals(1L, saved.getUserId());
        assertEquals("keycloak-sub-1", saved.getActorKeycloakSub());
        assertEquals("GET", saved.getMethod());
        assertEquals("/api/sites", saved.getPath());
        assertEquals(200, saved.getStatusCode());
        assertEquals(42L, saved.getDurationMs());
        assertTrue(saved.isSuccess());
        assertEquals(now, saved.getCreatedAt());
    }

    @Test
    void onOperationLog_所要時間と作成時刻が無ければ0と現在時刻で保存する() {
        LocalDateTime before = LocalDateTime.now().minusSeconds(2);
        OperationLogMessage message = new OperationLogMessage(
                "op-2", null, null, "POST", "/api/x", null, null, false, "boom", null);

        listener.onOperationLog(message);

        OperationLog saved = operationLogRepository.findAll().get(0);
        assertEquals(0L, saved.getDurationMs());
        assertEquals("boom", saved.getErrorMessage());
        assertFalse(saved.isSuccess());
        assertFalse(saved.getCreatedAt().isBefore(before));
    }

    @Test
    void onAuditLog_メッセージの内容をそのまま保存する() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        AuditLogMessage message = new AuditLogMessage(
                1L, "keycloak-sub-1", "USER_CREATED", "USER", 2L, "{}", "127.0.0.1", "agent", now.toString());

        listener.onAuditLog(message);

        AuditLog saved = onlyAuditLog();
        assertEquals(1L, saved.getUserId());
        assertEquals("keycloak-sub-1", saved.getActorKeycloakSub());
        assertEquals("USER_CREATED", saved.getAction());
        assertEquals("USER", saved.getResourceType());
        assertEquals(2L, saved.getResourceId());
        assertEquals("{}", saved.getChanges());
        assertEquals("127.0.0.1", saved.getRemoteIp());
        assertEquals("agent", saved.getUserAgent());
        assertEquals(now, saved.getCreatedAt());
    }

    @Test
    void onAuditLog_作成時刻が無ければ現在時刻で保存する() {
        LocalDateTime before = LocalDateTime.now().minusSeconds(2);
        AuditLogMessage message = new AuditLogMessage(1L, "s", "USER_CREATED", "USER", 2L, null, null, null, null);

        listener.onAuditLog(message);

        assertFalse(onlyAuditLog().getCreatedAt().isBefore(before));
    }

    @Test
    void onAuditLog_上限を超えるchangesはUTF8で65535バイト以内に切り詰めて保存する() {
        String big = "a" + "あ".repeat(30_000);

        listener.onAuditLog(audit(big, "127.0.0.1"));

        String saved = onlyAuditLog().getChanges();
        int bytes = saved.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes <= 65_535, "bytes=" + bytes);
        assertTrue(bytes > 65_535 - 3, "must keep as much as fits, bytes=" + bytes);
        assertTrue(big.startsWith(saved));
        assertFalse(saved.contains("�"));
    }

    @Test
    void onAuditLog_2バイト文字だけのchangesも65535バイト以内に切り詰める() {
        String big = "é".repeat(40_000);

        listener.onAuditLog(audit(big, null));

        String saved = onlyAuditLog().getChanges();
        assertEquals(32_767, saved.length());
        assertTrue(saved.getBytes(StandardCharsets.UTF_8).length <= 65_535);
    }

    @Test
    void onAuditLog_ちょうど上限のchangesはそのまま保存する() {
        String exact = "x".repeat(65_535);

        listener.onAuditLog(audit(exact, null));

        assertEquals(exact, onlyAuditLog().getChanges());
    }

    @Test
    void onAuditLog_3倍しても上限に収まる短いchangesはそのまま保存する() {
        listener.onAuditLog(audit("あ".repeat(100), null));

        assertEquals("あ".repeat(100), onlyAuditLog().getChanges());
    }

    @Test
    void onAuditLog_3倍すると上限を超えるが実バイト数では収まるchangesはそのまま保存する() {
        // 22_000 文字 × 3 = 66_000 > 65_535 だが、ASCII なので実際は 22_000 バイト。切り詰めない。
        String ascii = "x".repeat(22_000);

        listener.onAuditLog(audit(ascii, null));

        assertEquals(ascii, onlyAuditLog().getChanges());
    }

    @Test
    void onAuditLog_changesがnullでも保存できる() {
        listener.onAuditLog(audit(null, null));

        assertNull(onlyAuditLog().getChanges());
    }

    @Test
    void onAuditLog_サロゲートペアの途中で切らない() {
        String big = "x".repeat(65_533) + "😀😀";

        listener.onAuditLog(audit(big, null));

        assertEquals("x".repeat(65_533), onlyAuditLog().getChanges());
    }

    @Test
    void onAuditLog_45文字を超えるremoteIpは45文字以内に切り詰めて保存する() {
        String longIp = "2001:db8:85a3:0:0:8a2e:370:7334, 10.0.0.1, 10.0.0.2";

        listener.onAuditLog(audit(null, longIp));

        assertEquals(longIp.substring(0, 45), onlyAuditLog().getRemoteIp());
    }

    @Test
    void onAuditLog_ちょうど45文字のremoteIpはそのまま保存する() {
        String exact = "x".repeat(45);

        listener.onAuditLog(audit(null, exact));

        assertEquals(exact, onlyAuditLog().getRemoteIp());
    }
}
