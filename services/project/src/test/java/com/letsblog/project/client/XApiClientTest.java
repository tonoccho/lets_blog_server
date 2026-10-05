package com.letsblog.project.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** X API(OAuth 2.0 認可コードフローの認可 URL・トークン交換・自分の情報)のクライアント(issue #1574)。 */
class XApiClientTest {

    private HttpServer server;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private XApiClient client(int status, String response) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
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
        return new XApiClient(RestClient.builder(), "http://localhost:" + server.getAddress().getPort(),
                "https://x.example/i/oauth2/authorize");
    }

    @Test
    void 認可URLにクライアントとリダイレクト先とstateとPKCEとスコープを載せる() {
        XApiClient client = new XApiClient(RestClient.builder(), "https://api.example", "https://x.example/i/oauth2/authorize");

        URI uri = URI.create(client.authorizeUrl("cid", "https://localhost/connect/x/callback", "1.abc", "chal"));

        assertEquals("x.example", uri.getHost());
        assertEquals("/i/oauth2/authorize", uri.getPath());
        String query = uri.getQuery();
        assertTrue(query.contains("response_type=code"));
        assertTrue(query.contains("client_id=cid"));
        assertTrue(query.contains("redirect_uri=https://localhost/connect/x/callback"));
        assertTrue(query.contains("state=1.abc"));
        assertTrue(query.contains("code_challenge=chal"));
        assertTrue(query.contains("code_challenge_method=S256"));
        assertTrue(query.contains("scope=tweet.read tweet.write users.read offline.access"));
    }

    @Test
    void 認可コードをBasic認証つきのフォームでトークンに交換する() throws IOException {
        XApiClient client = client(200,
                "{\"token_type\":\"bearer\",\"expires_in\":7200,\"access_token\":\"AT\",\"refresh_token\":\"RT\"}");

        XApiClient.XTokens tokens = client.exchangeCode("cid", "csecret", "the-code", "https://l/cb", "verifier");

        assertEquals("AT", tokens.accessToken());
        assertEquals("RT", tokens.refreshToken());
        assertEquals(7200L, tokens.expiresIn());
        assertEquals("POST", method.get());
        assertEquals("/2/oauth2/token", path.get());
        assertEquals("Basic " + Base64.getEncoder().encodeToString("cid:csecret".getBytes(StandardCharsets.UTF_8)), auth.get());
        assertTrue(contentType.get().startsWith("application/x-www-form-urlencoded"));
        assertTrue(body.get().contains("grant_type=authorization_code"));
        assertTrue(body.get().contains("code=the-code"));
        assertTrue(body.get().contains("code_verifier=verifier"));
        assertTrue(body.get().contains("redirect_uri=https%3A%2F%2Fl%2Fcb"));
    }

    @Test
    void expires_inが無ければ既定の2時間とみなす() throws IOException {
        XApiClient client = client(200, "{\"access_token\":\"AT\",\"refresh_token\":\"RT\"}");

        assertEquals(7200L, client.exchangeCode("cid", "s", "c", "r", "v").expiresIn());
    }

    @Test
    void リフレッシュトークンが返らなければ失敗する() throws IOException {
        XApiClient client = client(200, "{\"access_token\":\"AT\",\"expires_in\":7200}");

        XApiException e = assertThrows(XApiException.class, () -> client.exchangeCode("cid", "s", "c", "r", "v"));
        assertTrue(e.getMessage().contains("offline.access"));
    }

    @Test
    void アクセストークンが返らなければ失敗する() throws IOException {
        XApiClient client = client(200, "{\"refresh_token\":\"RT\"}");

        assertThrows(XApiException.class, () -> client.exchangeCode("cid", "s", "c", "r", "v"));
    }

    @Test
    void 応答が空なら失敗する() throws IOException {
        XApiClient client = client(200, "");

        assertThrows(XApiException.class, () -> client.exchangeCode("cid", "s", "c", "r", "v"));
    }

    @Test
    void トークン交換が拒否されたら理由を含め_秘密は含めない() throws IOException {
        XApiClient client = client(400, "{\"error\":\"invalid_request\",\"error_description\":\"Value passed for the token was invalid.\"}");

        XApiException e = assertThrows(XApiException.class,
                () -> client.exchangeCode("cid", "csecret-value", "the-code", "r", "v"));

        assertTrue(e.getMessage().contains("HTTP 400"));
        assertTrue(e.getMessage().contains("Value passed for the token was invalid."));
        assertFalse(e.getMessage().contains("csecret-value"));
        assertFalse(e.getMessage().contains("the-code"));
    }

    @Test
    void 拒否の本文がJSONでなくても失敗として扱う() throws IOException {
        XApiClient client = client(500, "boom");

        XApiException e = assertThrows(XApiException.class, () -> client.exchangeCode("cid", "s", "c", "r", "v"));
        assertTrue(e.getMessage().contains("HTTP 500"));
    }

    @Test
    void 自分のユーザー名をBearerで取得する() throws IOException {
        XApiClient client = client(200, "{\"data\":{\"id\":\"1\",\"name\":\"N\",\"username\":\"lets_blog\"}}");

        assertEquals("lets_blog", client.fetchUsername("AT"));
        assertEquals("GET", method.get());
        assertEquals("/2/users/me", path.get());
        assertEquals("Bearer AT", auth.get());
    }

    @Test
    void ユーザー名が取れなければ失敗する() throws IOException {
        XApiClient client = client(200, "{\"data\":{}}");

        assertThrows(XApiException.class, () -> client.fetchUsername("AT"));
    }

    @Test
    void ユーザー情報の取得が拒否されたら失敗する() throws IOException {
        XApiClient client = client(401, "{\"title\":\"Unauthorized\",\"detail\":\"Unauthorized\"}");

        XApiException e = assertThrows(XApiException.class, () -> client.fetchUsername("AT"));
        assertTrue(e.getMessage().contains("HTTP 401"));
    }

    @Test
    void 自分の情報の応答が空なら失敗する() throws IOException {
        XApiClient client = client(200, "");

        assertThrows(XApiException.class, () -> client.fetchUsername("AT"));
    }

    @Test
    void ユーザー名が空文字なら失敗する() throws IOException {
        XApiClient client = client(200, "{\"data\":{\"username\":\"\"}}");

        assertThrows(XApiException.class, () -> client.fetchUsername("AT"));
    }

    @Test
    void 拒否の本文が空でも失敗として扱い_理由は付けない() throws IOException {
        XApiClient client = client(401, "");

        XApiException e = assertThrows(XApiException.class, () -> client.fetchUsername("AT"));
        assertEquals("X の自分の情報の取得に失敗しました(HTTP 401)", e.getMessage());
    }

    @Test
    void 理由が空の項目は読み飛ばし_長い理由は200文字に切る() throws IOException {
        String longDetail = "d".repeat(500);
        XApiClient client = client(403, "{\"error_description\":\"\",\"detail\":\"" + longDetail + "\"}");

        XApiException e = assertThrows(XApiException.class, () -> client.fetchUsername("AT"));

        assertTrue(e.getMessage().contains("d".repeat(200)));
        assertFalse(e.getMessage().contains("d".repeat(201)));
    }

    @Test
    void 接続できなければ失敗する() {
        XApiClient client = new XApiClient(RestClient.builder(), "http://localhost:1", "https://x.example/a");

        assertThrows(XApiException.class, () -> client.fetchUsername("AT"));
        assertThrows(XApiException.class, () -> client.exchangeCode("cid", "s", "c", "r", "v"));
    }
}
