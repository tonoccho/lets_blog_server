package com.letsblog.project.aop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.messaging.AuditLogMessage;
import com.letsblog.project.domain.AuditLogAction;
import com.letsblog.project.service.AuditLogService;
import com.letsblog.project.testsupport.ProjectWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
 * <p>issue #1818で書き直し: AuditLogAspectをSpringの実際のAOPプロキシ({@link AspectJProxyFactory})で
 * 適用し(ProceedingJoinPoint・MethodSignatureはMockitoで模さない)、AuditLogService・CurrentActorServiceは
 * 実物を使う。外部との通信は、MQへの送信({@code RabbitTemplate}、Mockito)と、identity-serviceへの問い合わせ
 * ({@code FakeHttpServer})だけを差し替える。
 */
class AuditLogAspectTest {

    private static final int MAX_BYTES = 65_535;

    private ProjectWorld world;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private Target target;
    private Target proxy;

    /** AuditLogを付けたメソッドを持つ業務処理の代役(自前のクラスの代わりではなく、アスペクトの対象)。 */
    public static class Target {
        Object result;
        RuntimeException failure;

        @AuditLog(action = AuditLogAction.SSH_KEY_PAIR_CREATED, resourceType = "T")
        public Object annotated(Long id) {
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        @AuditLog(action = AuditLogAction.SSH_KEY_PAIR_CREATED, resourceType = "T")
        public Object annotatedWithText(String text) {
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

    /** idの型がLongでないので、resourceIdには使えない。 */
    public static class TextIdRow {
        public String getId() {
            return "abc";
        }
    }

    /** {@code id()}形式のアクセサ(record)でidを持つ。 */
    public record RecordRow(Long id) {
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
        world = new ProjectWorld();
        world.actingAs(3L, "user", "u@example.com").signedInAs("sub-3");
        world.request.addHeader("User-Agent", "JUnit");
        world.request.addHeader("X-Forwarded-For", "203.0.113.5");
        AuditLogAspect aspect = new AuditLogAspect(new AuditLogService(world.rabbitTemplate),
                world.currentActorService, objectMapper);
        target = new Target();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        proxy = factory.getProxy();
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private AuditLogMessage published() {
        ArgumentCaptor<Object> message = ArgumentCaptor.forClass(Object.class);
        verify(world.rabbitTemplate).convertAndSend(anyString(), anyString(), message.capture());
        return (AuditLogMessage) message.getValue();
    }

    private String runAndCaptureChanges(Object result) {
        target.result = result;
        Object returned = proxy.annotated(7L);
        assertSame(result, returned);
        return published().changes();
    }

    @Test
    void 記録にはアクション_リソース種別_操作者_IP_UAが入る() {
        target.result = "plain";

        proxy.annotated(7L);

        AuditLogMessage message = published();
        assertEquals("SSH_KEY_PAIR_CREATED", message.action());
        assertEquals("T", message.resourceType());
        assertEquals(3L, message.userId());
        assertEquals("sub-3", message.actorKeycloakSub());
        assertEquals("203.0.113.5", message.remoteIp());
        assertEquals("JUnit", message.userAgent());
    }

    @Test
    void byte配列の戻り値は中身を記録せず種別とサイズだけを記録する() throws Exception {
        byte[] zip = new byte[300_000];
        Arrays.fill(zip, (byte) 0x50);

        String changes = runAndCaptureChanges(zip);

        JsonNode node = objectMapper.readTree(changes);
        assertEquals("binary", node.get("type").asText());
        assertEquals(300_000, node.get("size").asLong());
        assertFalse(changes.contains(Base64.getEncoder().encodeToString(new byte[] {0x50, 0x50, 0x50, 0x50})));
        assertTrue(changes.length() < 200);
    }

    @Test
    void 戻り値が呼び出し元へそのまま返り_byte配列でも監査は1件だけ記録される() {
        byte[] bytes = "PK".getBytes(StandardCharsets.UTF_8);
        target.result = bytes;

        Object returned = proxy.annotated(7L);

        assertArrayEquals(bytes, (byte[]) returned);
        published();
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
                return "x".repeat(999) + "😀" + "x".repeat(100_000);
            }
        };

        String changes = runAndCaptureChanges(surrogateAtCut);

        JsonNode node = objectMapper.readTree(changes);
        assertEquals(999, node.get("preview").asText().length());
        assertTrue(changes.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES);
    }

    @Test
    void 切り詰め位置がサロゲートペアでなければ上限の文字数で切る() throws Exception {
        Object longText = new Object() {
            public Object getBroken() {
                throw new IllegalStateException("boom");
            }

            @Override
            public String toString() {
                return "y".repeat(100_000);
            }
        };

        String changes = runAndCaptureChanges(longText);

        assertEquals(1_000, objectMapper.readTree(changes).get("preview").asText().length());
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
        target.result = Map.of("k", "v");
        doThrow(new IllegalStateException("mq down")).when(world.rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(Object.class));

        assertEquals(Map.of("k", "v"), proxy.annotated(7L));
    }

    @Test
    void 戻り値のidが取れなければLong引数をresourceIdにする() {
        target.result = "plain";

        proxy.annotated(7L);

        assertEquals(7L, published().resourceId());
    }

    @Test
    void 戻り値のgetIdをresourceIdにする() {
        target.result = new Row(42L, "n");

        proxy.annotated(7L);

        assertEquals(42L, published().resourceId());
    }

    @Test
    void 戻り値のrecordのidアクセサをresourceIdにする() {
        target.result = new RecordRow(43L);

        proxy.annotated(7L);

        assertEquals(43L, published().resourceId());
    }

    @Test
    void 戻り値のidがLongでなければ使わずLong引数をresourceIdにする() {
        target.result = new TextIdRow();

        proxy.annotated(7L);

        assertEquals(7L, published().resourceId());
    }

    @Test
    void resourceIdが特定できなければnull() {
        target.result = "plain";

        proxy.annotatedWithText("str");

        assertNull(published().resourceId());
    }

    @Test
    void 業務処理が例外なら監査ログは記録しない() {
        target.failure = new IllegalArgumentException("x");

        assertThrows(IllegalArgumentException.class, () -> proxy.annotated(7L));

        verify(world.rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }
}
