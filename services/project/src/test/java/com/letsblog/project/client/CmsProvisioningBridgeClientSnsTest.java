package com.letsblog.project.client;

import com.letsblog.project.cms.LetsblogSnsResult;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** SNS 告知の wp-cli 実行(接続・状態・テスト投稿・履歴)を publishing-service のブリッジへ依頼する(issue #1574)。 */
class CmsProvisioningBridgeClientSnsTest {

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private CmsProvisioningBridgeClient client(int status, String response) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer req");
        return new CmsProvisioningBridgeClient(
                RestClient.builder(), "http://localhost:" + server.getAddress().getPort(), request);
    }

    @Test
    void コマンドとSNSと標準入力と認証情報を送り標準出力を受け取る() throws IOException {
        CmsProvisioningBridgeClient client = client(200, "{\"stdout\":\"{\\\"status\\\":\\\"接続済み\\\"}\"}");

        LetsblogSnsResult result = client.letsblogSns(
                "WORDPRESS", Map.of("wpSlug", "s"), "config-set", "x", "{\"sns\":\"x\"}");

        assertEquals("{\"status\":\"接続済み\"}", result.stdout());
        assertEquals("/api/internal/project/cms/letsblog-sns", path.get());
        assertTrue(body.get().contains("\"command\":\"config-set\""));
        assertTrue(body.get().contains("\"sns\":\"x\""));
        assertTrue(body.get().contains("\"stdin\":\"{\\\"sns\\\":\\\"x\\\"}\""));
        assertTrue(body.get().contains("\"cmsType\":\"WORDPRESS\""));
    }

    @Test
    void 標準入力もSNSも無いコマンドはその項目を送らない() throws IOException {
        CmsProvisioningBridgeClient client = client(200, "{\"stdout\":\"[]\"}");

        client.letsblogSns("WORDPRESS", Map.of("wpSlug", "s"), "log", null, null);

        assertTrue(!body.get().contains("\"stdin\""));
        assertTrue(!body.get().contains("\"sns\""));
    }

    @Test
    void 失敗したら例外() throws IOException {
        CmsProvisioningBridgeClient client = client(502, "{\"message\":\"x\"}");

        assertThrows(CmsBridgeException.class,
                () -> client.letsblogSns("WORDPRESS", Map.of("wpSlug", "s"), "status", "x", null));
    }

    @Test
    void 応答が空なら例外() throws IOException {
        CmsProvisioningBridgeClient client = client(200, "");

        assertThrows(CmsBridgeException.class,
                () -> client.letsblogSns("WORDPRESS", Map.of("wpSlug", "s"), "status", "x", null));
    }
}
