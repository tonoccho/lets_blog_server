package com.letsblog.project.client;

import com.letsblog.project.service.IdentityServiceUnavailableException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** analytics-service から、本番サイトのプラグインへ送る GA4 の認証情報(復号済み)を取得する(issue #1578)。 */
class AnalyticsBridgeClientTest {

    private HttpServer server;
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private AnalyticsBridgeClient client(int status, String response) throws IOException {
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
        return new AnalyticsBridgeClient(RestClient.builder(), "http://localhost:" + server.getAddress().getPort());
    }

    @Test
    void 連携済みならプロパティIDとOAuthの認証情報を取得する() throws IOException {
        AnalyticsBridgeClient.GoogleAnalyticsCredentials credentials = client(200,
                "{\"configured\":true,\"propertyId\":\"987\",\"clientId\":\"cid\",\"clientSecret\":\"cs\",\"refreshToken\":\"rt\"}")
                .googleAnalyticsCredentials(7L, "Bearer t");

        assertTrue(credentials.configured());
        assertEquals("987", credentials.propertyId());
        assertEquals("cid", credentials.clientId());
        assertEquals("cs", credentials.clientSecret());
        assertEquals("rt", credentials.refreshToken());
        assertEquals("/api/internal/analytics/projects/7/google-analytics/credentials", path.get());
        assertEquals("Bearer t", auth.get());
    }

    @Test
    void 未連携なら設定済みでない状態を返す() throws IOException {
        AnalyticsBridgeClient.GoogleAnalyticsCredentials credentials =
                client(200, "{\"configured\":false}").googleAnalyticsCredentials(7L, "Bearer t");

        assertFalse(credentials.configured());
        assertNull(credentials.refreshToken());
    }

    @Test
    void トークンが無ければAuthorizationを付けない() throws IOException {
        client(200, "{\"configured\":false}").googleAnalyticsCredentials(7L, null);
        assertNull(auth.get());
    }

    @Test
    void トークンが空白ならAuthorizationを付けない() throws IOException {
        client(200, "{\"configured\":false}").googleAnalyticsCredentials(7L, " ");
        assertNull(auth.get());
    }

    @Test
    void 失敗したら例外で_認証情報は含めない() throws IOException {
        AnalyticsBridgeClient client = client(500, "{\"error\":\"boom\"}");

        IdentityServiceUnavailableException e =
                assertThrows(IdentityServiceUnavailableException.class, () -> client.googleAnalyticsCredentials(7L, "Bearer t"));
        assertTrue(e.getMessage().contains("analytics-service"));
    }

    @Test
    void 応答が空なら例外() throws IOException {
        AnalyticsBridgeClient client = client(200, "");

        assertThrows(IdentityServiceUnavailableException.class, () -> client.googleAnalyticsCredentials(7L, "Bearer t"));
    }
}
