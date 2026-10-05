package com.letsblog.project.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Threads API(OAuth 認可コードフローの認可 URL・短期トークンの交換・長期トークン化・自分の情報)のクライアント(issue #1579)。
 */
class ThreadsApiClientTest {

    private HttpServer server;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> query = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private ThreadsApiClient client(int status, String response) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            query.set(exchange.getRequestURI().getRawQuery());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        return new ThreadsApiClient(RestClient.builder(), "http://localhost:" + server.getAddress().getPort(),
                "https://threads.example/oauth/authorize");
    }

    @Test
    void 認可URLにクライアントとリダイレクト先とstateとスコープを載せる() {
        ThreadsApiClient client = new ThreadsApiClient(RestClient.builder(), "https://graph.example",
                "https://threads.example/oauth/authorize");

        URI uri = URI.create(client.authorizeUrl("app-id", "https://localhost/connect/threads/callback", "1.abc"));

        assertEquals("threads.example", uri.getHost());
        assertEquals("/oauth/authorize", uri.getPath());
        String q = uri.getQuery();
        assertTrue(q.contains("response_type=code"));
        assertTrue(q.contains("client_id=app-id"));
        assertTrue(q.contains("redirect_uri=https://localhost/connect/threads/callback"));
        assertTrue(q.contains("state=1.abc"));
        assertTrue(q.contains("scope=threads_basic,threads_content_publish"));
    }

    @Test
    void 認可コードをフォームで短期トークンとユーザーIDに交換する() throws IOException {
        ThreadsApiClient client = client(200, "{\"access_token\":\"SHORT\",\"user_id\":17841400000000001}");

        ThreadsApiClient.ShortLivedToken token = client.exchangeCode("app-id", "app-secret", "the-code", "https://l/cb");

        assertEquals("SHORT", token.accessToken());
        assertEquals("17841400000000001", token.userId());
        assertEquals("POST", method.get());
        assertEquals("/oauth/access_token", path.get());
        assertTrue(contentType.get().startsWith("application/x-www-form-urlencoded"));
        assertTrue(body.get().contains("client_id=app-id"));
        assertTrue(body.get().contains("client_secret=app-secret"));
        assertTrue(body.get().contains("grant_type=authorization_code"));
        assertTrue(body.get().contains("code=the-code"));
        assertTrue(body.get().contains("redirect_uri=https%3A%2F%2Fl%2Fcb"));
    }

    @Test
    void ユーザーIDが文字列で返ってもよい() throws IOException {
        ThreadsApiClient client = client(200, "{\"access_token\":\"SHORT\",\"user_id\":\"42\"}");

        assertEquals("42", client.exchangeCode("a", "s", "c", "r").userId());
    }

    @Test
    void 短期トークンかユーザーIDが返らなければ失敗する() throws IOException {
        ThreadsApiClient noToken = client(200, "{\"user_id\":1}");
        assertThrows(ThreadsApiException.class, () -> noToken.exchangeCode("a", "s", "c", "r"));
        server.stop(0);
        ThreadsApiClient noUser = client(200, "{\"access_token\":\"SHORT\"}");
        assertThrows(ThreadsApiException.class, () -> noUser.exchangeCode("a", "s", "c", "r"));
        server.stop(0);
        ThreadsApiClient empty = client(200, "");
        assertThrows(ThreadsApiException.class, () -> empty.exchangeCode("a", "s", "c", "r"));
    }

    @Test
    void 短期トークンを長期トークンに交換する() throws IOException {
        ThreadsApiClient client = client(200, "{\"access_token\":\"LONG\",\"token_type\":\"bearer\",\"expires_in\":5184000}");

        ThreadsApiClient.LongLivedToken token = client.exchangeLongLived("app-secret", "SHORT");

        assertEquals("LONG", token.accessToken());
        assertEquals(5184000L, token.expiresIn());
        assertEquals("GET", method.get());
        assertEquals("/access_token", path.get());
        assertTrue(query.get().contains("grant_type=th_exchange_token"));
        assertTrue(query.get().contains("client_secret=app-secret"));
        assertTrue(query.get().contains("access_token=SHORT"));
    }

    @Test
    void expires_inが無ければ既定の60日とみなす() throws IOException {
        ThreadsApiClient client = client(200, "{\"access_token\":\"LONG\"}");

        assertEquals(5184000L, client.exchangeLongLived("s", "SHORT").expiresIn());
    }

    @Test
    void 長期トークンが返らなければ失敗する() throws IOException {
        ThreadsApiClient client = client(200, "{\"expires_in\":5184000}");

        assertThrows(ThreadsApiException.class, () -> client.exchangeLongLived("s", "SHORT"));
        server.stop(0);
        ThreadsApiClient empty = client(200, "");
        assertThrows(ThreadsApiException.class, () -> empty.exchangeLongLived("s", "SHORT"));
    }

    @Test
    void 拒否されたら理由を含め_秘密とトークンは含めない() throws IOException {
        ThreadsApiClient client = client(400,
                "{\"error\":{\"message\":\"Invalid authorization code\",\"type\":\"OAuthException\",\"code\":100}}");

        ThreadsApiException e = assertThrows(ThreadsApiException.class,
                () -> client.exchangeCode("app-id", "secret-value", "the-code", "r"));

        assertTrue(e.getMessage().contains("HTTP 400"));
        assertTrue(e.getMessage().contains("Invalid authorization code"));
        assertFalse(e.getMessage().contains("secret-value"));
        assertFalse(e.getMessage().contains("the-code"));
    }

    @Test
    void 拒否の本文がJSONでなくても空でも失敗として扱う() throws IOException {
        ThreadsApiClient plain = client(500, "boom");
        ThreadsApiException e = assertThrows(ThreadsApiException.class, () -> plain.exchangeLongLived("s", "t"));
        assertTrue(e.getMessage().contains("HTTP 500"));
        server.stop(0);
        ThreadsApiClient empty = client(401, "");
        assertEquals("Threads の自分の情報の取得に失敗しました(HTTP 401)",
                assertThrows(ThreadsApiException.class, () -> empty.fetchUsername("AT")).getMessage());
    }

    @Test
    void 長い理由は200文字に切る() throws IOException {
        ThreadsApiClient client = client(403, "{\"error\":{\"message\":\"" + "d".repeat(500) + "\"}}");

        ThreadsApiException e = assertThrows(ThreadsApiException.class, () -> client.fetchUsername("AT"));

        assertTrue(e.getMessage().contains("d".repeat(200)));
        assertFalse(e.getMessage().contains("d".repeat(201)));
    }

    @Test
    void 自分のユーザー名をBearerで取得する() throws IOException {
        ThreadsApiClient client = client(200, "{\"id\":\"1\",\"username\":\"lets_blog\"}");

        assertEquals("lets_blog", client.fetchUsername("AT"));
        assertEquals("GET", method.get());
        assertEquals("/v1.0/me", path.get());
        assertTrue(query.get().contains("fields=id,username"));
        assertEquals("Bearer AT", auth.get());
    }

    @Test
    void ユーザー名が取れなければ失敗する() throws IOException {
        ThreadsApiClient client = client(200, "{\"id\":\"1\",\"username\":\"\"}");
        assertThrows(ThreadsApiException.class, () -> client.fetchUsername("AT"));
        server.stop(0);
        ThreadsApiClient empty = client(200, "");
        assertThrows(ThreadsApiException.class, () -> empty.fetchUsername("AT"));
    }

    @Test
    void 接続できなければ失敗し_メッセージに秘密を含めない() {
        ThreadsApiClient client = new ThreadsApiClient(RestClient.builder(), "http://localhost:1", "https://t.example/a");

        ThreadsApiException e1 = assertThrows(ThreadsApiException.class, () -> client.fetchUsername("AT"));
        ThreadsApiException e2 = assertThrows(ThreadsApiException.class, () -> client.exchangeCode("a", "s", "c", "r"));
        ThreadsApiException e3 = assertThrows(ThreadsApiException.class, () -> client.exchangeLongLived("secret-value", "SHORT-TOKEN"));

        assertTrue(e1.getMessage().contains("接続できません"));
        assertTrue(e2.getMessage().contains("接続できません"));
        assertTrue(e3.getMessage().contains("接続できません"));
        assertFalse(e3.getMessage().contains("secret-value"));
        assertFalse(e3.getMessage().contains("SHORT-TOKEN"));
        assertFalse(String.valueOf(e3.getCause()).contains("secret-value"), "原因の例外を保持すると URL の秘密がログに出る");
    }

    @Test
    void トークンの値は文字列表現に出ない() {
        assertFalse(new ThreadsApiClient.ShortLivedToken("SHORT", "1").toString().contains("SHORT"));
        assertFalse(new ThreadsApiClient.LongLivedToken("LONG", 1L).toString().contains("LONG"));
    }
}
