package com.letsblog.project.client;

import com.letsblog.project.cms.LetsblogSyncResult;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** プラグインへの同期(wp-cliだけ)を publishing-service のブリッジへ依頼する(issue #1558)。 */
class CmsProvisioningBridgeClientLetsblogSyncTest {

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();

    @AfterEach
    void tearDown() {
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        if (server != null) {
            server.stop(0);
        }
    }

    private CmsProvisioningBridgeClient client(int status, String response, HttpServletRequest request)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
        return new CmsProvisioningBridgeClient(
                RestClient.builder(), "http://localhost:" + server.getAddress().getPort(), request,
                mock(com.letsblog.common.auth.ServiceTokenClient.class));
    }

    private HttpServletRequest requestWithBearer(String bearer) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn(bearer);
        // リクエスト処理中の呼び出しを模す。リクエスト外はジョブのスレッド扱い(サービス自身のトークン。#1723)。
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        return request;
    }

    @Test
    void 内容とハッシュと認証情報を送り保存されたハッシュを受け取る() throws IOException {
        CmsProvisioningBridgeClient client = client(200, "{\"syncHash\":\"h1\"}", requestWithBearer("Bearer req"));

        LetsblogSyncResult result = client.syncLetsblogPlugin("WORDPRESS", Map.of("wpSlug", "s"), "{\"a\":1}", "h1");

        assertEquals("h1", result.syncHash());
        assertEquals("/api/internal/project/cms/sync-letsblog-plugin", path.get());
        assertEquals("Bearer req", auth.get());
        org.junit.jupiter.api.Assertions.assertTrue(body.get().contains("\"payload\":\"{\\\"a\\\":1}\""));
        org.junit.jupiter.api.Assertions.assertTrue(body.get().contains("\"hash\":\"h1\""));
        org.junit.jupiter.api.Assertions.assertTrue(body.get().contains("\"cmsType\":\"WORDPRESS\""));
    }

    @Test
    void 取り置いたトークンがあればリクエストが無くてもそれを使う() throws IOException {
        HttpServletRequest noRequest = mock(HttpServletRequest.class);
        when(noRequest.getHeader("Authorization")).thenThrow(new IllegalStateException("No thread-bound request"));
        CmsProvisioningBridgeClient client = client(200, "{\"syncHash\":\"h1\"}", noRequest);

        BearerScope.call("Bearer scoped", () ->
                client.syncLetsblogPlugin("WORDPRESS", Map.of("wpSlug", "s"), "{}", "h1"));

        assertEquals("Bearer scoped", auth.get());
    }

    @Test
    void トークンが無ければAuthorizationを付けない() throws IOException {
        CmsProvisioningBridgeClient client = client(200, "{\"syncHash\":\"h1\"}", requestWithBearer(null));

        client.syncLetsblogPlugin("WORDPRESS", Map.of("wpSlug", "s"), "{}", "h1");

        assertNull(auth.get());
    }

    @Test
    void 失敗したら例外() throws IOException {
        CmsProvisioningBridgeClient client = client(500, "{\"message\":\"x\"}", requestWithBearer("Bearer req"));

        assertThrows(CmsBridgeException.class,
                () -> client.syncLetsblogPlugin("WORDPRESS", Map.of("wpSlug", "s"), "{}", "h1"));
    }

    @Test
    void 応答が空なら例外() throws IOException {
        CmsProvisioningBridgeClient client = client(200, "", requestWithBearer("Bearer req"));

        assertThrows(CmsBridgeException.class,
                () -> client.syncLetsblogPlugin("WORDPRESS", Map.of("wpSlug", "s"), "{}", "h1"));
    }
}
