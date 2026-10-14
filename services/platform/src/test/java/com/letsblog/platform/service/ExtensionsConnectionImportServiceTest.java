package com.letsblog.platform.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * extensions のポータルから接続先を取り込む(issue #1783)。ポータルは実HTTPサーバー(loopback)で立てる。
 * 接続先の検査(ExtensionsPortalUrlPolicy)は名前解決を差し替えて通し、検査自体は別テストで固定している。
 */
@ExtendWith(MockitoExtension.class)
class ExtensionsConnectionImportServiceTest {

    private static final String DEFAULT_BODY = "["
            + conn("plantuml", "http://lbx-plantuml:8080", null)
            + "," + conn("comfyui", "http://lbx-comfyui:8188", null)
            + "," + conn("ollama", "http://lbx-ollama:11434", "http://lbx-ollama:11434/v1")
            + "," + conn("penpot", "http://lbx-penpot-frontend:8080", null)
            + "," + conn("gitlab", "http://lbx-gitlab", null)
            + "]";

    private static String conn(String id, String internalUrl, String openai) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"publicUrl\":\"https://" + id
                + ".lbx.localhost:8443\",\"internalUrl\":\"" + internalUrl + "\",\"internalServiceUrl\":\"http://" + id
                + "\"" + (openai == null ? "" : ",\"openaiBaseUrl\":\"" + openai + "\"") + "}";
    }

    @Mock
    private AppSettingService appSettingService;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status = 200;
    private volatile String body = DEFAULT_BODY;
    private volatile long delayMillis = 0;
    private String portalUrl;
    private ExtensionsConnectionImportService service;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/connections", exchange -> {
            requests.incrementAndGet();
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException ignored) {
                // クライアントがタイムアウトで切断した
            } finally {
                exchange.close();
            }
        });
        server.start();
        portalUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        ExtensionsPortalUrlPolicy permissive = new ExtensionsPortalUrlPolicy(
                host -> new InetAddress[] {InetAddress.getByName("192.168.1.20")});
        service = new ExtensionsConnectionImportService(
                appSettingService, adminAuthorizationService, permissive, Duration.ofMillis(400));
        lenient().when(appSettingService.getAllSettings()).thenReturn(List.of(
                status("llm_ollama_base_url", "Ollama ベースURL(OLLAMA用。OpenAI互換の /v1 まで含める)", "http://before:11434/v1"),
                status("comfyui_base_url", "ComfyUI ベースURL", null),
                status("penpot_base_url", "Penpot ベースURL", "http://192.168.77.10:3000")));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static AppSettingService.SettingStatus status(String key, String label, String value) {
        return new AppSettingService.SettingStatus(key, label, false, value != null,
                value != null ? AppSettingService.SettingSource.DATABASE : AppSettingService.SettingSource.NONE, value);
    }

    private static Map<String, String> candidates(ExtensionsConnectionImportService.FetchResult result) {
        Map<String, String> map = new LinkedHashMap<>();
        result.candidates().forEach(c -> map.put(c.key(), c.candidateValue()));
        return map;
    }

    @Test
    void 同じPCの経路ではOllamaはopenaiBaseUrl_ComfyUIはinternalUrl_Penpotはpublicurlになる() {
        ExtensionsConnectionImportService.FetchResult result = service.fetch(portalUrl, ExtensionsConnectionImportService.Route.SAME_PC);

        assertEquals(Map.of(
                "llm_ollama_base_url", "http://lbx-ollama:11434/v1",
                "comfyui_base_url", "http://lbx-comfyui:8188",
                "penpot_base_url", "https://penpot.lbx.localhost:8443"), candidates(result));
        assertEquals(List.of("llm_ollama_base_url", "comfyui_base_url", "penpot_base_url"),
                result.candidates().stream().map(ExtensionsConnectionImportService.Candidate::key).toList());
        assertEquals(List.of("plantuml", "gitlab"), result.ignoredIds());
    }

    @Test
    void 別ホストの経路では全てpublicUrlから組み立てOllamaだけv1が付く() {
        ExtensionsConnectionImportService.FetchResult result = service.fetch(portalUrl + "/", ExtensionsConnectionImportService.Route.OTHER_HOST);

        assertEquals(Map.of(
                "llm_ollama_base_url", "https://ollama.lbx.localhost:8443/v1",
                "comfyui_base_url", "https://comfyui.lbx.localhost:8443",
                "penpot_base_url", "https://penpot.lbx.localhost:8443"), candidates(result));
    }

    @Test
    void 候補には現在の値とラベルが並び未設定はnullになる() {
        ExtensionsConnectionImportService.FetchResult result = service.fetch(portalUrl, ExtensionsConnectionImportService.Route.SAME_PC);

        ExtensionsConnectionImportService.Candidate ollama = result.candidates().get(0);
        assertEquals("http://before:11434/v1", ollama.currentValue());
        assertTrue(ollama.label().startsWith("Ollama"), ollama.label());
        assertEquals(null, result.candidates().get(1).currentValue());
    }

    @Test
    void 応答に無いidの候補は作らず_未知のidは取り込み対象外として返しエラーにしない() {
        body = "[" + conn("ollama", "http://lbx-ollama:11434", "http://lbx-ollama:11434/v1")
                + "," + conn("mystery", "http://lbx-mystery", null) + "]";

        ExtensionsConnectionImportService.FetchResult result = service.fetch(portalUrl, ExtensionsConnectionImportService.Route.SAME_PC);

        assertEquals(List.of("llm_ollama_base_url"), List.copyOf(candidates(result).keySet()));
        assertEquals(List.of("mystery"), result.ignoredIds());
    }

    @Test
    void 同じidが重複したら先のものを使う() {
        body = "[" + conn("comfyui", "http://first:8188", null) + "," + conn("comfyui", "http://second:8188", null) + "]";

        assertEquals("http://first:8188",
                candidates(service.fetch(portalUrl, ExtensionsConnectionImportService.Route.SAME_PC)).get("comfyui_base_url"));
    }

    @Test
    void 取得はシステム設定を一切書き換えない() {
        service.fetch(portalUrl, ExtensionsConnectionImportService.Route.SAME_PC);

        verify(appSettingService, never()).updateSettings(anyMap());
    }

    @Test
    void ポータルが2xxでなければHTTPステータス付きのエラーで再試行しない() {
        status = 500;

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.fetch(portalUrl, ExtensionsConnectionImportService.Route.SAME_PC));

        assertEquals("ポータルがエラーを返しました(HTTP 500)", e.getMessage());
        assertEquals(1, requests.get());
    }

    @Test
    void ポータルが応答しなければタイムアウトのエラーになる() {
        delayMillis = 2000;

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.fetch(portalUrl, ExtensionsConnectionImportService.Route.SAME_PC));

        assertTrue(e.getMessage().contains("タイムアウト"), e.getMessage());
        assertEquals(1, requests.get());
    }

    @Test
    void ポータルに接続できなければ接続できないエラーになる() {
        server.stop(0);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.fetch(portalUrl, ExtensionsConnectionImportService.Route.SAME_PC));

        assertTrue(e.getMessage().startsWith("ポータルに接続できません"), e.getMessage());
    }

    @Test
    void JSONでない本文はJSONではないエラー() {
        body = "<html>not json</html>";

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.fetch(portalUrl, ExtensionsConnectionImportService.Route.SAME_PC));

        assertEquals("ポータルの応答がJSONではありません", e.getMessage());
    }

    @Test
    void 配列でないJSONは配列ではないエラー() {
        body = "{\"id\":\"ollama\"}";

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.fetch(portalUrl, ExtensionsConnectionImportService.Route.SAME_PC));

        assertEquals("ポータルの応答が配列ではありません", e.getMessage());
    }

    @Test
    void 要素がオブジェクトでない_またはidが無いと必須項目の欠落エラー() {
        for (String broken : List.of("[1]", "[{\"name\":\"x\"}]", "[{\"id\":5}]")) {
            body = broken;
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> service.fetch(portalUrl, ExtensionsConnectionImportService.Route.SAME_PC), broken);
            assertTrue(e.getMessage().contains("「id」"), e.getMessage());
        }
    }

    @Test
    void 取り込む対象のidが経路で使うURLを欠くと必須項目の欠落エラー() {
        body = "[{\"id\":\"penpot\",\"name\":\"Penpot\"}]";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.fetch(portalUrl, ExtensionsConnectionImportService.Route.SAME_PC));
        assertEquals("ポータルの応答の「penpot」に「publicUrl」がありません", e.getMessage());

        body = "[{\"id\":\"ollama\",\"publicUrl\":\"https://o\",\"internalUrl\":\"http://o\"}]";
        e = assertThrows(IllegalArgumentException.class,
                () -> service.fetch(portalUrl, ExtensionsConnectionImportService.Route.SAME_PC));
        assertEquals("ポータルの応答の「ollama」に「openaiBaseUrl」がありません", e.getMessage());

        body = "[{\"id\":\"comfyui\",\"publicUrl\":\"https://c\",\"internalUrl\":\"\"}]";
        e = assertThrows(IllegalArgumentException.class,
                () -> service.fetch(portalUrl, ExtensionsConnectionImportService.Route.SAME_PC));
        assertEquals("ポータルの応答の「comfyui」に「internalUrl」がありません", e.getMessage());
    }

    @Test
    void URLがhttpでもhttpsでもなければエラー() {
        body = "[{\"id\":\"penpot\",\"publicUrl\":\"ftp://penpot\"}]";

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.fetch(portalUrl, ExtensionsConnectionImportService.Route.SAME_PC));

        assertTrue(e.getMessage().contains("「penpot」の「publicUrl」"), e.getMessage());
        assertTrue(e.getMessage().contains("http://"), e.getMessage());
    }

    @Test
    void 取得のポータルのURLが禁止なら要求を出さずに拒否する() {
        ExtensionsConnectionImportService strict = new ExtensionsConnectionImportService(
                appSettingService, adminAuthorizationService, new ExtensionsPortalUrlPolicy(), Duration.ofMillis(400));

        assertThrows(IllegalArgumentException.class,
                () -> strict.fetch(portalUrl, ExtensionsConnectionImportService.Route.SAME_PC));
        assertEquals(0, requests.get());
    }

    @Test
    void 管理者でなければ取得は拒否され要求を出さない() {
        doThrow(new ForbiddenException("管理者のみ")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class,
                () -> service.fetch(portalUrl, ExtensionsConnectionImportService.Route.SAME_PC));
        assertEquals(0, requests.get());
    }

    // ---- 反映 ----

    @Test
    void 反映は検証で拒否されたキーだけ保存せず理由を返し_他は保存する() {
        doThrow(new IllegalArgumentException("penpot_base_url に指定できない接続先です。理由X"))
                .when(appSettingService).updateSettings(Map.of("penpot_base_url", "http://127.0.0.1:3000"));
        Map<String, String> values = new LinkedHashMap<>();
        values.put("llm_ollama_base_url", "http://lbx-ollama:11434/v1");
        values.put("penpot_base_url", "http://127.0.0.1:3000");
        values.put("comfyui_base_url", "http://lbx-comfyui:8188");

        ExtensionsConnectionImportService.ApplyResult result = service.apply("http://lbx-portal:3100", values);

        assertEquals(List.of("extensions_portal_url", "llm_ollama_base_url", "comfyui_base_url"), result.saved());
        assertEquals(1, result.rejected().size());
        assertEquals("penpot_base_url", result.rejected().get(0).key());
        assertEquals("penpot_base_url に指定できない接続先です。理由X", result.rejected().get(0).reason());
        verify(appSettingService).updateSettings(Map.of("extensions_portal_url", "http://lbx-portal:3100"));
        verify(appSettingService).updateSettings(Map.of("llm_ollama_base_url", "http://lbx-ollama:11434/v1"));
        verify(appSettingService).updateSettings(Map.of("comfyui_base_url", "http://lbx-comfyui:8188"));
    }

    @Test
    void 反映でポータルのURLが拒否されてもそのキーとして返し_他の保存は続ける() {
        doThrow(new IllegalArgumentException("extensions_portal_url は不可"))
                .when(appSettingService).updateSettings(Map.of("extensions_portal_url", "http://127.0.0.1:3100"));

        ExtensionsConnectionImportService.ApplyResult result = service.apply(
                "http://127.0.0.1:3100", Map.of("comfyui_base_url", "http://lbx-comfyui:8188"));

        assertEquals(List.of("comfyui_base_url"), result.saved());
        assertEquals("extensions_portal_url", result.rejected().get(0).key());
    }

    @Test
    void 反映は取り込み対象外のキーと空の値を保存せず理由を返す() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("mail_host", "evil.example");
        values.put("comfyui_base_url", "  ");
        values.put("penpot_base_url", null);

        ExtensionsConnectionImportService.ApplyResult result = service.apply(null, values);

        assertEquals(List.of(), result.saved());
        assertEquals(List.of("mail_host", "comfyui_base_url", "penpot_base_url"),
                result.rejected().stream().map(ExtensionsConnectionImportService.Rejection::key).toList());
        assertTrue(result.rejected().get(0).reason().contains("対象外"), result.rejected().get(0).reason());
        assertTrue(result.rejected().get(1).reason().contains("空"), result.rejected().get(1).reason());
        verify(appSettingService, never()).updateSettings(anyMap());
    }

    @Test
    void 反映はポータルのURLを指定しなければ値だけを保存する() {
        ExtensionsConnectionImportService.ApplyResult result = service.apply(
                "", Map.of("comfyui_base_url", "http://lbx-comfyui:8188"));

        assertEquals(List.of("comfyui_base_url"), result.saved());
        verify(appSettingService, never()).updateSettings(Map.of("extensions_portal_url", ""));
    }

    @Test
    void 管理者でなければ反映は拒否され何も保存しない() {
        doThrow(new ForbiddenException("管理者のみ")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class,
                () -> service.apply("http://lbx-portal:3100", Map.of("comfyui_base_url", "http://x")));
        verify(appSettingService, never()).updateSettings(anyMap());
    }

    @Test
    void 反映のvaluesがnullなら何も保存しない() {
        ExtensionsConnectionImportService.ApplyResult result = service.apply(null, null);

        assertEquals(List.of(), result.saved());
        assertEquals(List.of(), result.rejected());
        verify(appSettingService, never()).updateSettings(anyMap());
    }
}
