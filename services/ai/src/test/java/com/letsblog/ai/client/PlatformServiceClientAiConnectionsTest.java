package com.letsblog.ai.client;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PlatformServiceClient#resolveAiConnectionsConfig(issue #1499)。実HTTPサーバーに対して検証する。 */
class PlatformServiceClientAiConnectionsTest {

    private HttpServer server;
    private final AtomicReference<String> receivedAuthorization = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body = "";

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/platform/ai-connections-config", exchange -> {
            receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private PlatformServiceClient client() {
        return new PlatformServiceClient(
                RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    void 応答を各プロバイダーの設定として受け取りBearerトークンを転送する() {
        body = """
                {"ollama":{"baseUrl":"http://ollama:11434/v1","source":"ENVIRONMENT","configured":true},
                 "comfyui":{"baseUrl":"http://comfy:8188","source":"DATABASE","configured":true},
                 "openai":{"baseUrl":null,"source":"DATABASE","configured":true},
                 "claude":{"baseUrl":null,"source":"NONE","configured":false}}
                """;

        var config = client().resolveAiConnectionsConfig("Bearer t");

        assertEquals("Bearer t", receivedAuthorization.get());
        assertEquals("http://ollama:11434/v1", config.ollama().baseUrl());
        assertEquals("ENVIRONMENT", config.ollama().source());
        assertEquals("DATABASE", config.comfyui().source());
        assertNull(config.openai().baseUrl());
        assertTrue(config.openai().configured());
        assertFalse(config.claude().configured());
    }

    @Test
    void トークンが無ければAuthorizationヘッダーを付けない() {
        body = "{\"ollama\":null,\"comfyui\":null,\"openai\":null,\"claude\":null}";

        client().resolveAiConnectionsConfig(null);

        assertNull(receivedAuthorization.get());
    }

    @Test
    void 応答が空なら例外にする() {
        status = 200;
        body = "";

        assertThrows(IllegalStateException.class, () -> client().resolveAiConnectionsConfig("Bearer t"));
    }

    @Test
    void HTTPエラーは本文付きの例外にする() {
        status = 500;
        body = "boom";

        IllegalStateException e = assertThrows(
                IllegalStateException.class, () -> client().resolveAiConnectionsConfig("Bearer t"));
        assertEquals("boom", e.getMessage());
    }

    @Test
    void HTTPエラーで本文が空ならステータスのメッセージを使う() {
        status = 503;
        body = "";

        IllegalStateException e = assertThrows(
                IllegalStateException.class, () -> client().resolveAiConnectionsConfig("Bearer t"));
        assertTrue(e.getMessage().contains("503"), e.getMessage());
    }

    @Test
    void 接続できなければ原因付きの例外にする() {
        server.stop(0);

        IllegalStateException e = assertThrows(
                IllegalStateException.class, () -> client().resolveAiConnectionsConfig("Bearer t"));
        assertTrue(e.getMessage().contains("ai-connections-config"), e.getMessage());
    }
}
