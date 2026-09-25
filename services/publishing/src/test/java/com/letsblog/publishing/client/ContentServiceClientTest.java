package com.letsblog.publishing.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.auth.ServiceTokenClient;
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
 *
 * <p>issue #1207: {@code preview-skeleton/fetch-and-splice}・{@code fetch-real-post}の2エンドポイントは
 * 利用者単位の認可を一切行わないPlaywright専用ブリッジのため、呼び出し元ユーザーのBearerトークン転送
 * ({@code forwardedBearer})ではなく本サービス自身のClient Credentialsトークン({@link ServiceTokenClient}、
 * issue #567)を使う。media-serviceの{@code GenerationJobClientTest}(issue #1083)と同じく、
 * トークンエンドポイントも同じ{@link HttpServer}上の別パスとして待ち受ける。
 */
class ContentServiceClientTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HttpServer httpServer;
    private ServiceTokenClient serviceTokenClient;

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

    /**
     * issue #1207: fetch-and-splice/fetch-real-postは利用者単位の認可を行わないPlaywright専用
     * ブリッジのため、呼び出し元ユーザーのAuthorizationヘッダー({@code "Bearer test-token"})では
     * なく、本サービス自身のClient Credentialsトークン({@code "Bearer service-token-1"}、
     * {@link #serviceTokenClient}参照)が付与されることを固定する。
     */
    @Test
    void fetchAndSplice_呼び出し元のBearerではなく自身のClientCredentialsトークンを付与する() throws IOException {
        AtomicReference<List<String>> receivedAuth = new AtomicReference<>();
        httpServer = startHttpServer(exchange -> {
            receivedAuth.set(exchange.getRequestHeaders().get("Authorization"));
            readBody(exchange);
            respond(exchange, 200,
                    "{\"html\":\"<article>page</article>\",\"available\":true,\"reason\":null,"
                            + "\"eyecatchSpliced\":true,\"css\":\"\"}");
        });
        ContentServiceClient client = newClient();

        client.fetchAndSplice(
                "http://example.com/post", "参照タイトル", "参照本文", "自分のタイトル", "<p>自分の本文</p>", null);

        assertNotNull(receivedAuth.get());
        assertEquals(List.of("Bearer service-token-1"), receivedAuth.get());
    }

    @Test
    void fetchRealPost_呼び出し元のBearerではなく自身のClientCredentialsトークンを付与する() throws IOException {
        AtomicReference<List<String>> receivedAuth = new AtomicReference<>();
        httpServer = startHttpServer(exchange -> {
            receivedAuth.set(exchange.getRequestHeaders().get("Authorization"));
            readBody(exchange);
            respond(exchange, 200,
                    "{\"html\":\"<article>page</article>\",\"available\":true,\"reason\":null,"
                            + "\"eyecatchSpliced\":false,\"css\":\"\"}");
        });
        ContentServiceClient client = newClient();

        client.fetchRealPost("http://example.com/post?p=1", "wordpress_logged_in_x", "cookie-value");

        assertNotNull(receivedAuth.get());
        assertEquals(List.of("Bearer service-token-1"), receivedAuth.get());
    }

    private ContentServiceClient newClient() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.addHeader("Authorization", "Bearer test-token");
        return new ContentServiceClient(
                RestClient.builder(), baseUrl(httpServer), servletRequest, serviceTokenClient(httpServer));
    }

    /**
     * {@link ServiceTokenClient}が問い合わせるClient Credentialsトークンエンドポイントを、
     * 呼び出し元のHTTPサーバーと同じ{@link HttpServer}上の別パス({@code /token})として待ち受ける
     * (media-serviceのGenerationJobClientTest、issue #1083と同じ手法)。
     */
    private ServiceTokenClient serviceTokenClient(HttpServer server) {
        if (serviceTokenClient == null) {
            server.createContext("/token", exchange -> {
                readBody(exchange);
                respond(exchange, 200, "{\"access_token\":\"service-token-1\",\"expires_in\":3600}");
            });
            serviceTokenClient = new ServiceTokenClient(
                    RestClient.builder(), baseUrl(server) + "/token", "letsblog-services", "test-secret");
        }
        return serviceTokenClient;
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
