package com.letsblog.project.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.letsblog.project.service.IdentityServiceUnavailableException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** content-service から、サイトへ送る内容(タグ定義・統合CSS等)とそのハッシュを取得する(issue #1558)。 */
class ContentBridgeClientTest {

    private HttpServer server;
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private ContentBridgeClient client(int status, String response) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            path.set(exchange.getRequestURI().getPath());
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        return new ContentBridgeClient(RestClient.builder(), "http://localhost:" + server.getAddress().getPort());
    }

    @Test
    void 同期内容とハッシュを取得する() throws IOException {
        ContentBridgeClient.SyncPayload payload =
                client(200, "{\"payload\":\"{\\\"a\\\":1}\",\"hash\":\"h1\"}").fetchSyncPayload(7L, "Bearer t");

        assertEquals("{\"a\":1}", payload.payload());
        assertEquals("h1", payload.hash());
        assertEquals("/api/internal/content/projects/7/letsblog-sync-payload", path.get());
        assertEquals("Bearer t", auth.get());
    }

    @Test
    void トークンが無ければAuthorizationを付けない() throws IOException {
        client(200, "{\"payload\":\"{}\",\"hash\":\"h\"}").fetchSyncPayload(7L, null);

        assertNull(auth.get());
    }

    @Test
    void 失敗したら例外() throws IOException {
        ContentBridgeClient client = client(500, "{}");

        assertThrows(IdentityServiceUnavailableException.class, () -> client.fetchSyncPayload(7L, "Bearer t"));
    }

    @Test
    void 応答が空なら例外() throws IOException {
        ContentBridgeClient client = client(200, "");

        assertThrows(IdentityServiceUnavailableException.class, () -> client.fetchSyncPayload(7L, "Bearer t"));
    }

    @Test
    void トークンが空白ならAuthorizationを付けない() throws IOException {
        client(200, "{\"payload\":\"{}\",\"hash\":\"h\"}").fetchSyncPayload(7L, " ");

        assertNull(auth.get());
    }
}
