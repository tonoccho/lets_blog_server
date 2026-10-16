package com.letsblog.platform.aop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.messaging.AuditLogMessage;
import com.letsblog.platform.domain.AuditLogAction;
import com.letsblog.platform.testsupport.PlatformWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 監査ログのchangesへ、バイナリや上限超のペイロードを入れない(issue #1246)。
 *
 * <p>issue #1825: アスペクトは、ProceedingJoinPoint / MethodSignatureをMockitoで模すのをやめ、Springの実際のAOPプロキシ
 * ({@link AspectJProxyFactory})越しに{@code @AuditLog}付きメソッドを呼んで通す。AuditLogService・CurrentActorService・
 * AdminAuthorizationServiceは実物で、差し替えるのは外部との通信(identity-serviceのHTTPフェイクとMQの
 * {@code RabbitTemplate})だけ。
 */
class AuditLogAspectTest {

    private static final int MAX_BYTES = 65_535;

    /** {@code @AuditLog}を付けた業務処理の代わり。戻り値と例外は各テストが決める。 */
    public static class Target {
        Object result;
        RuntimeException failure;

        @AuditLog(action = AuditLogAction.DB_BACKUP_DOWNLOADED, resourceType = "T")
        public Object act(Object argument) {
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    public static class Row {
        public final Long id;
        public final String note;

        Row(Long id, String note) {
            this.id = id;
            this.note = note;
        }

        public Long getId() {
            return id;
        }

        public String getNote() {
            return note;
        }
    }

    private static class Unserializable {
        public Object getBroken() {
            throw new IllegalStateException("boom");
        }

        @Override
        public String toString() {
            return "Unserializable";
        }
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PlatformWorld world;
    private Target target;
    private Target proxy;

    @BeforeEach
    void setUp() throws Exception {
        world = new PlatformWorld().actingAsAdmin(9L);
        world.request.setRemoteAddr("10.0.0.1");
        world.request.addHeader("User-Agent", "ua");
        world.signedInAs("sub-9");
        AuditLogAspect aspect = new AuditLogAspect(world.auditLogService, world.currentActorService, objectMapper);
        target = new Target();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        proxy = factory.getProxy();
    }

    @AfterEach
    void tearDown() throws Exception {
        world.close();
    }

    private AuditLogMessage lastAudit() {
        ArgumentCaptor<Object> message = ArgumentCaptor.forClass(Object.class);
        verify(world.rabbitTemplate).convertAndSend(anyString(), anyString(), message.capture());
        return (AuditLogMessage) message.getValue();
    }

    private String runAndCaptureChanges(Object result) {
        target.result = result;
        Object returned = proxy.act(7L);
        assertSame(result, returned);
        AuditLogMessage audit = lastAudit();
        assertEquals("DB_BACKUP_DOWNLOADED", audit.action());
        assertEquals("T", audit.resourceType());
        return audit.changes();
    }

    @Test
    void byte配列の戻り値は中身を記録せず種別とサイズだけを記録する() throws Exception {
        byte[] zip = new byte[300_000];
        java.util.Arrays.fill(zip, (byte) 0x50);

        String changes = runAndCaptureChanges(zip);

        JsonNode node = objectMapper.readTree(changes);
        assertEquals("binary", node.get("type").asText());
        assertEquals(300_000, node.get("size").asLong());
        assertFalse(changes.contains(Base64.getEncoder().encodeToString(new byte[]{0x50, 0x50, 0x50, 0x50})));
        assertTrue(changes.length() < 200);
    }

    @Test
    void 戻り値が呼び出し元へそのまま返り_byte配列でも監査は1件だけ記録される() throws Exception {
        byte[] bytes = "PK".getBytes(StandardCharsets.UTF_8);
        target.result = bytes;

        Object returned = proxy.act(7L);

        assertArrayEquals(bytes, (byte[]) returned);
        AuditLogMessage audit = lastAudit();
        assertEquals(9L, audit.userId());
        assertEquals("sub-9", audit.actorKeycloakSub());
        assertEquals("10.0.0.1", audit.remoteIp());
        assertEquals("ua", audit.userAgent());
        assertEquals(7L, audit.resourceId());
    }

    @Test
    void 上限を超えるJSONは上限内の有効なJSONへ切り詰めて記録する() throws Exception {
        String big = "あ".repeat(50_000);

        String changes = runAndCaptureChanges(new Row(1L, big));

        assertTrue(changes.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES);
        JsonNode node = objectMapper.readTree(changes);
        assertTrue(node.get("truncated").asBoolean());
        assertTrue(node.get("originalSize").asLong() > MAX_BYTES);
        assertTrue(node.get("preview").asText().length() > 0);
    }

    @Test
    void 上限内のJSONはそのまま記録する() throws Exception {
        String changes = runAndCaptureChanges(new Row(1L, "small"));

        assertEquals(objectMapper.writeValueAsString(new Row(1L, "small")), changes);
    }

    @Test
    void シリアライズできない戻り値のtoString文字列も上限を超えれば切り詰める() throws Exception {
        Object hugeToString = new Object() {
            public Object getBroken() {
                throw new IllegalStateException("boom");
            }

            @Override
            public String toString() {
                return "x".repeat(100_000);
            }
        };

        String changes = runAndCaptureChanges(hugeToString);

        assertTrue(changes.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES);
        assertTrue(objectMapper.readTree(changes).get("truncated").asBoolean());
    }

    @Test
    void 切り詰め位置がサロゲートペアの途中なら手前で切り_有効なJSONにする() throws Exception {
        Object surrogateAtCut = new Object() {
            public Object getBroken() {
                throw new IllegalStateException("boom");
            }

            @Override
            public String toString() {
                return "x".repeat(999) + "\uD83D\uDE00" + "x".repeat(100_000);
            }
        };

        String changes = runAndCaptureChanges(surrogateAtCut);

        JsonNode node = objectMapper.readTree(changes);
        assertEquals(999, node.get("preview").asText().length());
        assertTrue(changes.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES);
    }

    @Test
    void シリアライズできない戻り値の短いtoStringはそのまま記録する() throws Exception {
        String changes = runAndCaptureChanges(new Unserializable());

        assertEquals("Unserializable", changes);
    }

    @Test
    void 戻り値がnullならchangesもnull() throws Exception {
        assertNull(runAndCaptureChanges(null));
    }

    @Test
    void 記録に失敗しても業務処理の戻り値は返す() throws Exception {
        target.result = Map.of("k", "v");
        doThrow(new IllegalStateException("mq down")).when(world.rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(Object.class));

        assertEquals(Map.of("k", "v"), proxy.act(7L));
    }

    @Test
    void 戻り値のidが取れなければLong引数をresourceIdにする() throws Exception {
        target.result = "plain";
        proxy.act(7L);
        assertEquals(7L, lastAudit().resourceId());
    }

    @Test
    void 戻り値のidをresourceIdにする() throws Exception {
        target.result = new Row(42L, "n");
        proxy.act(7L);
        assertEquals(42L, lastAudit().resourceId());
    }

    @Test
    void resourceIdが特定できなければnull() throws Exception {
        target.result = "plain";
        proxy.act("str");
        assertNull(lastAudit().resourceId());
    }

    @Test
    void 業務処理が例外なら監査ログは記録しない() throws Exception {
        target.failure = new IllegalArgumentException("x");

        assertThrows(IllegalArgumentException.class, () -> proxy.act(7L));

        verify(world.rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    public static class StringIdHolder {
        public String getId() {
            return "not-a-long";
        }
    }

    @Test
    void 戻り値のidがLongでなければLong引数をresourceIdにする() throws Exception {
        target.result = new StringIdHolder();
        proxy.act(7L);
        assertEquals(7L, lastAudit().resourceId());
    }
}
