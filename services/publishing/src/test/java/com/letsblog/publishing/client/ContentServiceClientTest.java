package com.letsblog.publishing.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.publishing.service.InvalidRechartsTagException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;

/**
 * ContentServiceClientの単体テスト(issue #759)。プロジェクト未紐付けサイト(projectId=null)でも
 * リクエストボディの組み立てで落ちず、content-serviceへ{@code projectId}をnullとして送れることを、
 * 実際のHTTPサーバー(JDK標準の{@link HttpServer})を使って検証する
 * (SyncServiceClientTestと同じ手法)。
 */
class ContentServiceClientTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HttpServer httpServer;

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    void renderPreImage_projectIdがnullでも例外にならずprojectIdをnullとして送る() throws IOException {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        httpServer = startHttpServer(exchange -> {
            receivedBody.set(readBody(exchange));
            respond(exchange, 200, "{\"markdown\":\"rendered\"}");
        });
        ContentServiceClient client = newClient();

        String result = client.renderPreImage("# 本文", null, false);

        assertEquals("rendered", result);
        JsonNode body = OBJECT_MAPPER.readTree(receivedBody.get());
        assertEquals("# 本文", body.path("markdown").asText());
        assertTrue(
                body.path("projectId").isNull() || body.path("projectId").isMissingNode(),
                "未紐付けサイトではprojectIdはnullとして送られる。実際: " + receivedBody.get());
        assertEquals(false, body.path("productionSite").asBoolean());
    }

    @Test
    void renderPreImage_projectId紐付け済みなら従来どおり値を送る() throws IOException {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        httpServer = startHttpServer(exchange -> {
            receivedBody.set(readBody(exchange));
            respond(exchange, 200, "{\"markdown\":\"rendered\"}");
        });
        ContentServiceClient client = newClient();

        String result = client.renderPreImage("# 本文", 7L, true);

        assertEquals("rendered", result);
        JsonNode body = OBJECT_MAPPER.readTree(receivedBody.get());
        assertEquals("# 本文", body.path("markdown").asText());
        assertEquals(7L, body.path("projectId").asLong());
        assertEquals(true, body.path("productionSite").asBoolean());
    }

    @Test
    void renderPreImage_markdownがnullなら空文字として送る() throws IOException {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        httpServer = startHttpServer(exchange -> {
            receivedBody.set(readBody(exchange));
            respond(exchange, 200, "{\"markdown\":\"\"}");
        });
        ContentServiceClient client = newClient();

        client.renderPreImage(null, null, false);

        JsonNode body = OBJECT_MAPPER.readTree(receivedBody.get());
        assertEquals("", body.path("markdown").asText());
    }

    @Test
    void renderPreImage_400はInvalidRechartsTagExceptionへ変換する() throws IOException {
        httpServer = startHttpServer(exchange -> respond(exchange, 400, "rechartsタグが不正です"));
        ContentServiceClient client = newClient();

        InvalidRechartsTagException e =
                assertThrows(InvalidRechartsTagException.class, () -> client.renderPreImage("# 本文", null, false));

        assertNotNull(e.getMessage());
        assertTrue(e.getMessage().contains("recharts"), "content-serviceの応答ボディをメッセージに含める");
    }

    @Test
    void finalizeHtml_projectIdがnullでも例外にならずprojectIdをnullとして送る() throws IOException {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        httpServer = startHttpServer(exchange -> {
            receivedBody.set(readBody(exchange));
            respond(exchange, 200, "{\"html\":\"<p>本文</p>\"}");
        });
        ContentServiceClient client = newClient();

        String result = client.finalizeHtml("本文", null);

        assertEquals("<p>本文</p>", result);
        JsonNode body = OBJECT_MAPPER.readTree(receivedBody.get());
        assertEquals("本文", body.path("markdown").asText());
        assertTrue(
                body.path("projectId").isNull() || body.path("projectId").isMissingNode(),
                "未紐付けサイトではprojectIdはnullとして送られる。実際: " + receivedBody.get());
    }

    @Test
    void finalizeHtml_projectId紐付け済みなら従来どおり値を送る() throws IOException {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        httpServer = startHttpServer(exchange -> {
            receivedBody.set(readBody(exchange));
            respond(exchange, 200, "{\"html\":\"<p>本文</p>\"}");
        });
        ContentServiceClient client = newClient();

        String result = client.finalizeHtml("本文", 7L);

        assertEquals("<p>本文</p>", result);
        JsonNode body = OBJECT_MAPPER.readTree(receivedBody.get());
        assertEquals(7L, body.path("projectId").asLong());
    }

    @Test
    void findPostBySlug_siteキーとスラッグで既存投稿を引き当て_呼び出し元のBearerを転送する() throws IOException {
        AtomicReference<String> receivedPath = new AtomicReference<>();
        AtomicReference<List<String>> receivedAuth = new AtomicReference<>();
        httpServer = startHttpServer(exchange -> {
            receivedPath.set(exchange.getRequestURI().getRawPath());
            receivedAuth.set(exchange.getRequestHeaders().get("Authorization"));
            respond(exchange, 200, "{\"wpPostId\":\"55\",\"status\":\"publish\"}");
        });

        var found = newClient().findPostBySlug("test-site", "my-slug");

        assertTrue(found.isPresent());
        assertEquals("55", found.get().wpPostId());
        assertEquals("publish", found.get().status());
        assertEquals("/api/posts/test-site/by-slug/my-slug", receivedPath.get());
        assertEquals(List.of("Bearer test-token"), receivedAuth.get());
    }

    @Test
    void findPostBySlug_404は該当なしとして空を返す() throws IOException {
        httpServer = startHttpServer(exchange -> respond(exchange, 404, "not found"));

        assertTrue(newClient().findPostBySlug("test-site", "my-slug").isEmpty());
    }

    @Test
    void findPostBySlug_404以外のHTTPエラーはIllegalStateExceptionにして本文を含める() throws IOException {
        httpServer = startHttpServer(exchange -> respond(exchange, 500, "内部エラー"));

        IllegalStateException e = assertThrows(
                IllegalStateException.class, () -> newClient().findPostBySlug("test-site", "my-slug"));

        assertTrue(e.getMessage().contains("内部エラー"), e.getMessage());
    }

    @Test
    void findPostBySlug_本文が空の応答は該当なしとして空を返す() throws IOException {
        httpServer = startHttpServer(exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        assertTrue(newClient().findPostBySlug("test-site", "my-slug").isEmpty());
    }

    @Test
    void findPostBySlug_接続できなければIllegalStateExceptionにする() throws IOException {
        httpServer = startHttpServer(exchange -> respond(exchange, 200, "{}"));
        ContentServiceClient client = newClient();
        httpServer.stop(0);

        assertThrows(IllegalStateException.class, () -> client.findPostBySlug("test-site", "my-slug"));
    }

    @Test
    void findPost_はZ付きの予約日時と最終公開日時を同じ実時刻として読む() throws IOException {
        httpServer = startHttpServer(exchange -> respond(exchange, 200,
                "{\"siteId\":1,\"wpPostId\":\"42\",\"slug\":\"s\",\"status\":\"future\",\"uploadedImagesJson\":null,"
                        + "\"categories\":null,\"publishScheduledAt\":\"2026-12-25T09:00:00Z\","
                        + "\"lastPublishedAt\":\"2026-09-08T20:03:35Z\"}"));

        ContentServiceClient.PostBridgeResponse post = newClient().findPost(1L, "42").orElseThrow();

        assertEquals(java.time.LocalDateTime.of(2026, 12, 25, 9, 0, 0), post.publishScheduledAt());
        assertEquals(java.time.LocalDateTime.of(2026, 9, 8, 20, 3, 35), post.lastPublishedAt());
    }

    @Test
    void findPost_は日時がnullの応答も読める() throws IOException {
        httpServer = startHttpServer(exchange -> respond(exchange, 200,
                "{\"siteId\":1,\"wpPostId\":\"42\",\"slug\":\"s\",\"status\":\"draft\",\"publishScheduledAt\":null,"
                        + "\"lastPublishedAt\":null}"));

        ContentServiceClient.PostBridgeResponse post = newClient().findPost(1L, "42").orElseThrow();

        assertEquals(null, post.publishScheduledAt());
        assertEquals(null, post.lastPublishedAt());
    }

    private ContentServiceClient newClient() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.addHeader("Authorization", "Bearer test-token");
        return new ContentServiceClient(RestClient.builder(), baseUrl(httpServer), servletRequest);
    }

    private HttpServer startHttpServer(HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    private String baseUrl(HttpServer server) {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", status == 200 ? "application/json" : "text/plain");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
