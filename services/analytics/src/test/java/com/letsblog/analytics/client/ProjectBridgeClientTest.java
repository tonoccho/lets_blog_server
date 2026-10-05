package com.letsblog.analytics.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * GA のプロパティを選び終えた(連携が完了した)ことを project-service へ知らせる(issue #1578)。
 * 知らせるのは付随の処理なので、届かなくても GA 連携そのものは失敗にしない。
 */
class ProjectBridgeClientTest {

    private HttpServer server;
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private ProjectBridgeClient client(int status) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            path.set(exchange.getRequestURI().getPath());
            method.set(exchange.getRequestMethod());
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        return new ProjectBridgeClient(RestClient.builder(), "http://localhost:" + server.getAddress().getPort());
    }

    @Test
    void 連携の完了を呼び出し元のトークンつきでPOSTする() throws IOException {
        client(204).notifyGoogleAnalyticsConnected(7L, "Bearer t");

        assertEquals("POST", method.get());
        assertEquals("/api/internal/project/projects/7/sns/pv/sync", path.get());
        assertEquals("Bearer t", auth.get());
    }

    @Test
    void トークンが無ければAuthorizationを付けない() throws IOException {
        client(204).notifyGoogleAnalyticsConnected(7L, null);

        assertNull(auth.get());
        assertEquals(1, calls.get());
    }

    @Test
    void project_serviceが失敗を返しても例外にしない() throws IOException {
        ProjectBridgeClient client = client(500);

        assertDoesNotThrow(() -> client.notifyGoogleAnalyticsConnected(7L, "Bearer t"));
        assertEquals(1, calls.get());
    }

    @Test
    void project_serviceへ届かなくても例外にしない() throws IOException {
        ProjectBridgeClient client = client(204);
        server.stop(0);
        server = null;

        assertDoesNotThrow(() -> client.notifyGoogleAnalyticsConnected(7L, "Bearer t"));
    }
}
