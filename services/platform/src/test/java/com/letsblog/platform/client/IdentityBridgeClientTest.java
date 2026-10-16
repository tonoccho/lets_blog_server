package com.letsblog.platform.client;

import com.letsblog.platform.service.IdentityServiceUnavailableException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** identity-serviceへのプロジェクトメンバー判定(issue #1808)。ai-serviceの同名クライアントと同じ経路を、実際のHTTPで確かめる。 */
class IdentityBridgeClientTest {

    private HttpServer server;
    private IdentityBridgeClient client;
    private final AtomicReference<String> requestedPath = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body = "true";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requestedPath.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = new IdentityBridgeClient(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void メンバーならtrueを返し_呼び出し元のBearerトークンを転送する() {
        assertTrue(client.isProjectMember(10L, 5L, "Bearer t"));

        assertEquals("/api/internal/identity/projects/10/members/5", requestedPath.get());
        assertEquals("Bearer t", authorization.get());
    }

    @Test
    void メンバーでなければfalse_トークンが無ければAuthorizationを付けない() {
        body = "false";

        assertFalse(client.isProjectMember(10L, 5L, " "));
        assertNull(authorization.get());
        assertFalse(client.isProjectMember(10L, 5L, null));
    }

    @Test
    void identity_serviceが失敗したら利用不能の例外にする() {
        status = 500;
        body = "{}";

        assertThrows(IdentityServiceUnavailableException.class, () -> client.isProjectMember(10L, 5L, "Bearer t"));
    }
}
