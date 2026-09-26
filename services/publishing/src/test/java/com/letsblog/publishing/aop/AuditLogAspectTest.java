package com.letsblog.publishing.aop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.publishing.domain.AuditLogAction;
import com.letsblog.publishing.service.AuditLogService;
import com.letsblog.publishing.service.CurrentActorService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 監査ログのchangesへ、バイナリや上限超のペイロードを入れない(issue #1246)。 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class AuditLogAspectTest {

    private static final int MAX_BYTES = 65_535;

    @Mock
    private AuditLogService auditLogService;
    @Mock
    private CurrentActorService currentActorService;
    @Mock
    private ProceedingJoinPoint joinPoint;
    @Mock
    private MethodSignature signature;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AuditLogAspect aspect;

    @AuditLog(action = AuditLogAction.POST_PUBLISHED, resourceType = "T")
    public Object annotated(Long id) {
        return null;
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

    @BeforeEach
    void setUp() throws Exception {
        aspect = new AuditLogAspect(auditLogService, currentActorService, objectMapper);
        Method method = AuditLogAspectTest.class.getMethod("annotated", Long.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs()).thenReturn(new Object[]{7L});
    }

    private String runAndCaptureChanges(Object result) throws Throwable {
        when(joinPoint.proceed()).thenReturn(result);
        Object returned = aspect.auditLogAdvice(joinPoint);
        assertSame(result, returned);
        ArgumentCaptor<String> changes = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).log(any(), any(), eq(AuditLogAction.POST_PUBLISHED), eq("T"), any(), changes.capture(),
                any(), any());
        return changes.getValue();
    }

    @Test
    void byte配列の戻り値は中身を記録せず種別とサイズだけを記録する() throws Throwable {
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
    void 戻り値が呼び出し元へそのまま返り_byte配列でも監査は1件だけ記録される() throws Throwable {
        byte[] bytes = "PK".getBytes(StandardCharsets.UTF_8);
        when(joinPoint.proceed()).thenReturn(bytes);

        Object returned = aspect.auditLogAdvice(joinPoint);

        assertArrayEquals(bytes, (byte[]) returned);
        verify(auditLogService).log(any(), any(), any(), anyString(), any(), anyString(), any(), any());
    }

    @Test
    void 上限を超えるJSONは上限内の有効なJSONへ切り詰めて記録する() throws Throwable {
        String big = "あ".repeat(50_000);

        String changes = runAndCaptureChanges(new Row(1L, big));

        assertTrue(changes.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES);
        JsonNode node = objectMapper.readTree(changes);
        assertTrue(node.get("truncated").asBoolean());
        assertTrue(node.get("originalSize").asLong() > MAX_BYTES);
        assertTrue(node.get("preview").asText().length() > 0);
    }

    @Test
    void 上限内のJSONはそのまま記録する() throws Throwable {
        String changes = runAndCaptureChanges(new Row(1L, "small"));

        assertEquals(objectMapper.writeValueAsString(new Row(1L, "small")), changes);
    }

    @Test
    void シリアライズできない戻り値のtoString文字列も上限を超えれば切り詰める() throws Throwable {
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
    void 切り詰め位置がサロゲートペアの途中なら手前で切り_有効なJSONにする() throws Throwable {
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
    void シリアライズできない戻り値の短いtoStringはそのまま記録する() throws Throwable {
        String changes = runAndCaptureChanges(new Unserializable());

        assertEquals("Unserializable", changes);
    }

    @Test
    void 戻り値がnullならchangesもnull() throws Throwable {
        assertNull(runAndCaptureChanges(null));
    }

    @Test
    void 記録に失敗しても業務処理の戻り値は返す() throws Throwable {
        when(joinPoint.proceed()).thenReturn(Map.of("k", "v"));
        doThrow(new IllegalStateException("mq down")).when(auditLogService)
                .log(any(), any(), any(), anyString(), any(), any(), any(), any());

        assertEquals(Map.of("k", "v"), aspect.auditLogAdvice(joinPoint));
    }

    @Test
    void 戻り値のidが取れなければLong引数をresourceIdにする() throws Throwable {
        when(joinPoint.proceed()).thenReturn("plain");
        aspect.auditLogAdvice(joinPoint);
        verify(auditLogService).log(any(), any(), any(), anyString(), eq(7L), any(), any(), any());
    }

    @Test
    void 戻り値のidをresourceIdにする() throws Throwable {
        when(joinPoint.proceed()).thenReturn(new Row(42L, "n"));
        aspect.auditLogAdvice(joinPoint);
        verify(auditLogService).log(any(), any(), any(), anyString(), eq(42L), any(), any(), any());
    }

    @Test
    void resourceIdが特定できなければnull() throws Throwable {
        when(joinPoint.getArgs()).thenReturn(new Object[]{"str"});
        when(joinPoint.proceed()).thenReturn("plain");
        aspect.auditLogAdvice(joinPoint);
        verify(auditLogService).log(any(), any(), any(), anyString(), eq((Long) null), any(), any(), any());
    }

    @Test
    void 業務処理が例外なら監査ログは記録しない() throws Throwable {
        when(joinPoint.proceed()).thenThrow(new IllegalArgumentException("x"));
        try {
            aspect.auditLogAdvice(joinPoint);
        } catch (IllegalArgumentException expected) {
            // expected
        }
        verify(auditLogService, never()).log(any(), any(), any(), anyString(), any(), any(), any(), any());
    }
}
