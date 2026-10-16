package com.letsblog.content.aop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.messaging.AuditLogMessage;
import com.letsblog.common.messaging.LogExchanges;
import com.letsblog.content.domain.AuditLogAction;
import com.letsblog.content.testsupport.BrokenObjectMapper;
import com.letsblog.content.testsupport.ContentWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 監査ログのchangesへ、バイナリや上限超のペイロードを入れない(issue #1246)。
 *
 * <p>アスペクトは実物の{@link AuditLogAspect}を、Springの{@link AspectJProxyFactory}で{@link AuditLog}付きメソッドへ
 * 実際に織り込んで動かす({@code ProceedingJoinPoint}・{@code MethodSignature}は模さない)。
 * {@code AuditLogService}・{@code CurrentActorService}も実物で、差し替えるのは外部との通信(MQの
 * {@code RabbitTemplate}、identity-serviceのHTTP)だけ({@link ContentWorld})。
 */
class AuditLogAspectTest {

    private static final int MAX_BYTES = 65_535;

    private ContentWorld world;
    private Target target;

    /** {@link AuditLog}を付けた業務メソッドを持つ対象。 */
    public static class Target {
        @AuditLog(action = AuditLogAction.CUSTOM_TAG_CREATED, resourceType = "T")
        public Object annotated(Long id, Supplier<Object> body) {
            return body.get();
        }

        @AuditLog(action = AuditLogAction.CUSTOM_TAG_CREATED, resourceType = "T")
        public Object annotatedWithText(String text, Supplier<Object> body) {
            return body.get();
        }

        /** {@link AuditLog}が無いので織り込まれない。 */
        public Object plain(Supplier<Object> body) {
            return body.get();
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

    /** idを返すアクセサはあるが、戻り値がLongではない。 */
    public static class TextId {
        public String getId() {
            return "abc";
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

    @BeforeEach
    void setUp() {
        world = new ContentWorld();
        target = proxyOf(new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private Target proxyOf(ObjectMapper mapper) {
        AspectJProxyFactory factory = new AspectJProxyFactory(new Target());
        factory.addAspect(new AuditLogAspect(world.auditLogService, world.currentActorService, mapper));
        return factory.getProxy();
    }

    private AuditLogMessage published() {
        ArgumentCaptor<Object> message = ArgumentCaptor.forClass(Object.class);
        verify(world.rabbitTemplate).convertAndSend(
                eq(LogExchanges.LOG_EXCHANGE), eq(LogExchanges.AUDIT_LOG_ROUTING_KEY), message.capture());
        assertEquals(AuditLogAction.CUSTOM_TAG_CREATED.name(), ((AuditLogMessage) message.getValue()).action());
        assertEquals("T", ((AuditLogMessage) message.getValue()).resourceType());
        return (AuditLogMessage) message.getValue();
    }

    private String runAndCaptureChanges(Object result) {
        Object returned = target.annotated(7L, () -> result);
        assertSame(result, returned);
        return published().changes();
    }

    @Test
    void byte配列の戻り値は中身を記録せず種別とサイズだけを記録する() throws Exception {
        byte[] zip = new byte[300_000];
        Arrays.fill(zip, (byte) 0x50);

        String changes = runAndCaptureChanges(zip);

        JsonNode node = new ObjectMapper().readTree(changes);
        assertEquals("binary", node.get("type").asText());
        assertEquals(300_000, node.get("size").asLong());
        assertFalse(changes.contains(Base64.getEncoder().encodeToString(new byte[]{0x50, 0x50, 0x50, 0x50})));
        assertTrue(changes.length() < 200);
    }

    @Test
    void 戻り値が呼び出し元へそのまま返り_byte配列でも監査は1件だけ記録される() {
        byte[] bytes = "PK".getBytes(StandardCharsets.UTF_8);

        Object returned = target.annotated(7L, () -> bytes);

        assertArrayEquals(bytes, (byte[]) returned);
        assertEquals("{\"type\":\"binary\",\"size\":2}", published().changes());
    }

    @Test
    void 上限を超えるJSONは上限内の有効なJSONへ切り詰めて記録する() throws Exception {
        String big = "あ".repeat(50_000);

        String changes = runAndCaptureChanges(new Row(1L, big));

        assertTrue(changes.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES);
        JsonNode node = new ObjectMapper().readTree(changes);
        assertTrue(node.get("truncated").asBoolean());
        assertTrue(node.get("originalSize").asLong() > MAX_BYTES);
        assertTrue(node.get("preview").asText().length() > 0);
    }

    @Test
    void 上限内のJSONはそのまま記録する() throws Exception {
        String changes = runAndCaptureChanges(new Row(1L, "small"));

        assertEquals(new ObjectMapper().writeValueAsString(new Row(1L, "small")), changes);
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
        assertTrue(new ObjectMapper().readTree(changes).get("truncated").asBoolean());
    }

    @Test
    void 切り詰め位置がサロゲートペアの途中なら手前で切り_有効なJSONにする() throws Exception {
        Object surrogateAtCut = new Object() {
            public Object getBroken() {
                throw new IllegalStateException("boom");
            }

            @Override
            public String toString() {
                return "x".repeat(999) + "😀" + "x".repeat(100_000);
            }
        };

        String changes = runAndCaptureChanges(surrogateAtCut);

        JsonNode node = new ObjectMapper().readTree(changes);
        assertEquals(999, node.get("preview").asText().length());
        assertTrue(changes.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES);
    }

    @Test
    void 要約のJSON化にも失敗するときは_originalSizeだけの固定JSONを記録する() {
        // 値の書き出しが必ず失敗する実物のObjectMapper: 戻り値はtoStringへ、要約もMapの書き出しに失敗して固定JSONになる。
        Target broken = proxyOf(BrokenObjectMapper.create());
        Object huge = new Object() {
            @Override
            public String toString() {
                return "x".repeat(100_000);
            }
        };

        broken.annotated(7L, () -> huge);

        assertEquals("{\"truncated\":true,\"originalSize\":100000}", published().changes());
    }

    @Test
    void シリアライズできない戻り値の短いtoStringはそのまま記録する() {
        String changes = runAndCaptureChanges(new Unserializable());

        assertEquals("Unserializable", changes);
    }

    @Test
    void 戻り値がnullならchangesもnull() {
        assertNull(runAndCaptureChanges(null));
    }

    @Test
    void 記録に失敗しても業務処理の戻り値は返す() {
        doThrow(new IllegalStateException("mq down")).when(world.rabbitTemplate)
                .convertAndSend(eq(LogExchanges.LOG_EXCHANGE), eq(LogExchanges.AUDIT_LOG_ROUTING_KEY), any(Object.class));

        assertEquals(Map.of("k", "v"), target.annotated(7L, () -> Map.of("k", "v")));
    }

    @Test
    void 操作者の情報を取れなくても業務処理の戻り値は返し_監査は記録しない() {
        world.identityServiceDown().actingAsUser(5L);

        assertEquals("ok", target.annotated(7L, () -> "ok"));

        verify(world.rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
    }

    @Test
    void 操作者_JWTのsub_接続元_UserAgentを監査ログへ記録する() {
        world.actingAsUser(5L).signedInAs("kc-sub-1");
        world.request.setRemoteAddr("192.0.2.9");
        world.request.addHeader("User-Agent", "JUnit");

        target.annotated(7L, () -> "plain");

        AuditLogMessage message = published();
        assertEquals(5L, message.userId());
        assertEquals("kc-sub-1", message.actorKeycloakSub());
        assertEquals("192.0.2.9", message.remoteIp());
        assertEquals("JUnit", message.userAgent());
    }

    @Test
    void 戻り値のidが取れなければLong引数をresourceIdにする() {
        target.annotated(7L, () -> "plain");

        assertEquals(7L, published().resourceId());
    }

    @Test
    void 戻り値のidをresourceIdにする() {
        target.annotated(7L, () -> new Row(42L, "n"));

        assertEquals(42L, published().resourceId());
    }

    @Test
    void 戻り値のidアクセサがLongを返さなければLong引数をresourceIdにする() {
        target.annotated(7L, TextId::new);

        assertEquals(7L, published().resourceId());
    }

    @Test
    void resourceIdが特定できなければnull() {
        target.annotatedWithText("str", () -> "plain");

        assertNull(published().resourceId());
    }

    @Test
    void 業務処理が例外なら監査ログは記録しない() {
        assertThrows(IllegalArgumentException.class, () -> target.annotated(7L, () -> {
            throw new IllegalArgumentException("x");
        }));

        verify(world.rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
    }

    @Test
    void AuditLogの無いメソッドは監査対象にならない() {
        assertEquals("v", target.plain(() -> "v"));

        verify(world.rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
    }
}
