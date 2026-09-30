package com.letsblog.media.client;

import com.letsblog.common.auth.ServiceTokenClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AiServiceConnectionClient(issue #1503)。ai-serviceの内部API
 * {@code /api/internal/ai/projects/{projectId}/connections}からComfyUI接続先の上書きを取得する。
 * サービストークンで認証し、プロジェクト別に5秒だけ結果を使い回し、null/空は「上書き無し」を意味する。
 * 実HTTPサーバーで検証する(GenerationJobClientTestと同じ理由でMockRestServiceServerは差し込まない)。
 */
class AiServiceConnectionClientTest {

    private HttpServer server;
    private AiServiceConnectionClient client;
    private MutableClock clock;
    private final List<String> requestPaths = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private volatile int status = 200;

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-30T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/ai/projects/", this::respond);
        server.start();
        String baseUri = "http://127.0.0.1:" + server.getAddress().getPort();
        ServiceTokenClient tokens = org.mockito.Mockito.mock(ServiceTokenClient.class);
        org.mockito.Mockito.when(tokens.getAccessToken()).thenReturn("svc-token");
        clock = new MutableClock();
        client = new AiServiceConnectionClient(RestClient.builder(), baseUri, tokens, clock);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void respond(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requestPaths.add(path);
        authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] bytes = bodies.getOrDefault(path, "{}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void 上書きがあればそのURLをサービストークンで取得する() {
        bodies.put("/api/internal/ai/projects/7/connections",
                "{\"ollamaBaseUrl\":\"http://o\",\"comfyuiBaseUrl\":\"http://gpu:8188\"}");

        assertThat(client.comfyUiBaseUrlOverride(7L)).isEqualTo("http://gpu:8188");
        assertThat(authHeaders).containsExactly("Bearer svc-token");
    }

    @Test
    void nullまたは空文字は上書き無しとしてnullを返す() {
        bodies.put("/api/internal/ai/projects/7/connections", "{\"comfyuiBaseUrl\":null}");
        bodies.put("/api/internal/ai/projects/8/connections", "{\"comfyuiBaseUrl\":\"  \"}");

        assertThat(client.comfyUiBaseUrlOverride(7L)).isNull();
        assertThat(client.comfyUiBaseUrlOverride(8L)).isNull();
    }

    @Test
    void 同じプロジェクトは5秒間は再取得せず期限が切れたら取り直す() {
        bodies.put("/api/internal/ai/projects/7/connections", "{\"comfyuiBaseUrl\":\"http://a\"}");

        client.comfyUiBaseUrlOverride(7L);
        clock.advance(Duration.ofSeconds(4));
        client.comfyUiBaseUrlOverride(7L);
        assertThat(requestPaths).hasSize(1);

        bodies.put("/api/internal/ai/projects/7/connections", "{\"comfyuiBaseUrl\":\"http://b\"}");
        clock.advance(Duration.ofSeconds(2));
        assertThat(client.comfyUiBaseUrlOverride(7L)).isEqualTo("http://b");
        assertThat(requestPaths).hasSize(2);
    }

    @Test
    void キャッシュはプロジェクト別で他プロジェクトの上書きは混ざらない() {
        bodies.put("/api/internal/ai/projects/7/connections", "{\"comfyuiBaseUrl\":\"http://seven\"}");
        bodies.put("/api/internal/ai/projects/8/connections", "{}");

        assertThat(client.comfyUiBaseUrlOverride(7L)).isEqualTo("http://seven");
        assertThat(client.comfyUiBaseUrlOverride(8L)).isNull();
        assertThat(client.comfyUiBaseUrlOverride(7L)).isEqualTo("http://seven");
        assertThat(new ArrayList<>(requestPaths)).containsExactly(
                "/api/internal/ai/projects/7/connections", "/api/internal/ai/projects/8/connections");
    }

    @Test
    void ai_serviceの失敗は握りつぶさず例外にする() {
        status = 500;

        assertThatThrownBy(() -> client.comfyUiBaseUrlOverride(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ai-service");
    }

    @Test
    void サービストークンを取得できない場合も例外にする() {
        ServiceTokenClient broken = org.mockito.Mockito.mock(ServiceTokenClient.class);
        org.mockito.Mockito.when(broken.getAccessToken())
                .thenThrow(new com.letsblog.common.auth.ServiceTokenUnavailableException("down", null));
        AiServiceConnectionClient c = new AiServiceConnectionClient(
                RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort(), broken, clock);

        assertThatThrownBy(() -> c.comfyUiBaseUrlOverride(7L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 応答本文が空でも上書き無しとして扱う() {
        bodies.put("/api/internal/ai/projects/9/connections", "");

        assertThat(client.comfyUiBaseUrlOverride(9L)).isNull();
    }
}
