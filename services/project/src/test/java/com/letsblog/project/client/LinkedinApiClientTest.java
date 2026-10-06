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
 * LinkedIn API(OAuth 2.0 認可コードフローの認可 URL・アクセストークンの交換・userinfo の sub と名前)のクライアント(issue #1581)。
 */
class LinkedinApiClientTest {

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

    private LinkedinApiClient client(int status, String response) throws IOException {
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
        String base = "http://localhost:" + server.getAddress().getPort();
        return new LinkedinApiClient(RestClient.builder(), base, base + "/oauth/v2/accessToken",
                "https://www.linkedin.example/oauth/v2/authorization");
    }

    private static LinkedinApiClient unreachable() {
        return new LinkedinApiClient(RestClient.builder(), "http://localhost:1", "http://localhost:1/oauth/v2/accessToken",
                "https://l.example/a");
    }

    @Test
    void 認可URLにクライアントとリダイレクト先とstateとスコープを載せる() {
        LinkedinApiClient client = new LinkedinApiClient(RestClient.builder(), "https://api.example",
                "https://www.linkedin.example/oauth/v2/accessToken", "https://www.linkedin.example/oauth/v2/authorization");

        URI uri = URI.create(client.authorizeUrl("app-id", "https://localhost/connect/linkedin/callback", "1.abc"));

        assertEquals("www.linkedin.example", uri.getHost());
        assertEquals("/oauth/v2/authorization", uri.getPath());
        String q = uri.getRawQuery();
        assertTrue(q.contains("response_type=code"));
        assertTrue(q.contains("client_id=app-id"));
        assertTrue(q.contains("redirect_uri=https://localhost/connect/linkedin/callback"));
        assertTrue(q.contains("state=1.abc"));
        assertTrue(q.contains("scope=openid%20profile%20w_member_social"));
    }

    @Test
    void 認可コードをフォームでアクセストークンと有効期限に交換する() throws IOException {
        LinkedinApiClient client = client(200, "{\"access_token\":\"AT\",\"expires_in\":5184000,\"scope\":\"openid,profile,w_member_social\"}");

        LinkedinApiClient.AccessToken token = client.exchangeCode("app-id", "app-secret", "the-code", "https://l/cb");

        assertEquals("AT", token.accessToken());
        assertEquals(5184000L, token.expiresIn());
        assertEquals("POST", method.get());
        assertEquals("/oauth/v2/accessToken", path.get());
        assertTrue(contentType.get().startsWith("application/x-www-form-urlencoded"));
        assertTrue(body.get().contains("client_id=app-id"));
        assertTrue(body.get().contains("client_secret=app-secret"));
        assertTrue(body.get().contains("grant_type=authorization_code"));
        assertTrue(body.get().contains("code=the-code"));
        assertTrue(body.get().contains("redirect_uri=https%3A%2F%2Fl%2Fcb"));
    }

    @Test
    void expires_inが無ければ既定の60日とみなす() throws IOException {
        LinkedinApiClient client = client(200, "{\"access_token\":\"AT\"}");

        assertEquals(5184000L, client.exchangeCode("a", "s", "c", "r").expiresIn());
    }

    @Test
    void アクセストークンが返らなければ失敗する() throws IOException {
        LinkedinApiClient noToken = client(200, "{\"expires_in\":5184000}");
        assertThrows(LinkedinApiException.class, () -> noToken.exchangeCode("a", "s", "c", "r"));
        server.stop(0);
        LinkedinApiClient empty = client(200, "");
        assertThrows(LinkedinApiException.class, () -> empty.exchangeCode("a", "s", "c", "r"));
    }

    @Test
    void 拒否されたら理由を含め_秘密とコードは含めない() throws IOException {
        LinkedinApiClient client = client(400,
                "{\"error\":\"invalid_request\",\"error_description\":\"The provided authorization grant is invalid\"}");

        LinkedinApiException e = assertThrows(LinkedinApiException.class,
                () -> client.exchangeCode("app-id", "secret-value", "the-code", "r"));

        assertTrue(e.getMessage().contains("HTTP 400"));
        assertTrue(e.getMessage().contains("The provided authorization grant is invalid"));
        assertFalse(e.getMessage().contains("secret-value"));
        assertFalse(e.getMessage().contains("the-code"));
    }

    @Test
    void API形式のmessageとerrorだけの本文からも理由を取り出す() throws IOException {
        LinkedinApiClient withMessage = client(401, "{\"status\":401,\"message\":\"Invalid access token\"}");
        assertTrue(assertThrows(LinkedinApiException.class, () -> withMessage.fetchProfile("AT"))
                .getMessage().contains("Invalid access token"));
        server.stop(0);
        LinkedinApiClient errorOnly = client(400, "{\"error\":\"invalid_client\"}");
        assertTrue(assertThrows(LinkedinApiException.class, () -> errorOnly.exchangeCode("a", "s", "c", "r"))
                .getMessage().contains("invalid_client"));
    }

    @Test
    void 拒否の本文がJSONでなくても空でも失敗として扱う() throws IOException {
        LinkedinApiClient plain = client(500, "boom");
        LinkedinApiException e = assertThrows(LinkedinApiException.class, () -> plain.exchangeCode("a", "s", "c", "r"));
        assertTrue(e.getMessage().contains("HTTP 500"));
        server.stop(0);
        LinkedinApiClient empty = client(401, "");
        assertEquals("LinkedIn の自分の情報の取得に失敗しました(HTTP 401)",
                assertThrows(LinkedinApiException.class, () -> empty.fetchProfile("AT")).getMessage());
    }

    @Test
    void 長い理由は200文字に切る() throws IOException {
        LinkedinApiClient client = client(403, "{\"message\":\"" + "d".repeat(500) + "\"}");

        LinkedinApiException e = assertThrows(LinkedinApiException.class, () -> client.fetchProfile("AT"));

        assertTrue(e.getMessage().contains("d".repeat(200)));
        assertFalse(e.getMessage().contains("d".repeat(201)));
    }

    @Test
    void userinfoからsubと名前をBearerで取得する() throws IOException {
        LinkedinApiClient client = client(200, "{\"sub\":\"abc123\",\"name\":\"Let's Blog\",\"given_name\":\"G\"}");

        LinkedinApiClient.Profile profile = client.fetchProfile("AT");

        assertEquals("abc123", profile.sub());
        assertEquals("Let's Blog", profile.name());
        assertEquals("GET", method.get());
        assertEquals("/v2/userinfo", path.get());
        assertEquals("Bearer AT", auth.get());
    }

    @Test
    void 名前が無ければ姓名を連結し_それも無ければsubを名前にする() throws IOException {
        LinkedinApiClient given = client(200, "{\"sub\":\"abc\",\"given_name\":\"Taro\",\"family_name\":\"Yamada\"}");
        assertEquals("Taro Yamada", given.fetchProfile("AT").name());
        server.stop(0);
        LinkedinApiClient onlyGiven = client(200, "{\"sub\":\"abc\",\"given_name\":\"Taro\"}");
        assertEquals("Taro", onlyGiven.fetchProfile("AT").name());
        server.stop(0);
        LinkedinApiClient onlyFamily = client(200, "{\"sub\":\"abc\",\"family_name\":\"Yamada\"}");
        assertEquals("Yamada", onlyFamily.fetchProfile("AT").name());
        server.stop(0);
        LinkedinApiClient onlySub = client(200, "{\"sub\":\"abc\"}");
        assertEquals("abc", onlySub.fetchProfile("AT").name());
    }

    @Test
    void subが取れなければ失敗する() throws IOException {
        LinkedinApiClient client = client(200, "{\"sub\":\"\",\"name\":\"x\"}");
        assertThrows(LinkedinApiException.class, () -> client.fetchProfile("AT"));
        server.stop(0);
        LinkedinApiClient empty = client(200, "");
        assertThrows(LinkedinApiException.class, () -> empty.fetchProfile("AT"));
    }

    @Test
    void 接続できなければ失敗し_メッセージに秘密を含めない() {
        LinkedinApiClient client = unreachable();

        LinkedinApiException e1 = assertThrows(LinkedinApiException.class, () -> client.fetchProfile("AT"));
        LinkedinApiException e2 = assertThrows(LinkedinApiException.class, () -> client.exchangeCode("a", "secret-value", "c", "r"));

        assertTrue(e1.getMessage().contains("接続できません"));
        assertTrue(e2.getMessage().contains("接続できません"));
        assertFalse(e2.getMessage().contains("secret-value"));
        assertFalse(String.valueOf(e2.getCause()).contains("secret-value"), "原因の例外を保持しない");
    }

    @Test
    void トークンの値は文字列表現に出ない() {
        assertFalse(new LinkedinApiClient.AccessToken("AT-VALUE", 1L).toString().contains("AT-VALUE"));
    }
}
