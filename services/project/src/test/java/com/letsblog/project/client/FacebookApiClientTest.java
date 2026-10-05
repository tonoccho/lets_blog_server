package com.letsblog.project.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Facebook(Graph API)の OAuth 認可コードフロー(認可 URL・コード交換・長期ユーザートークン化・管理ページの一覧)の
 * クライアント(issue #1580)。ページのトークンは長期ユーザートークンから得る。
 */
class FacebookApiClientTest {

    private HttpServer server;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> query = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private FacebookApiClient client(int status, String response) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            query.set(exchange.getRequestURI().getRawQuery());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        return new FacebookApiClient(RestClient.builder(), "http://localhost:" + server.getAddress().getPort(),
                "https://facebook.example/dialog/oauth");
    }

    @Test
    void 認可URLにクライアントとリダイレクト先とstateとページ投稿のスコープを載せる() {
        FacebookApiClient client = new FacebookApiClient(RestClient.builder(), "https://graph.example",
                "https://facebook.example/dialog/oauth");

        URI uri = URI.create(client.authorizeUrl("app-id", "https://localhost/connect/facebook/callback", "1.abc"));

        assertEquals("facebook.example", uri.getHost());
        assertEquals("/dialog/oauth", uri.getPath());
        String q = uri.getQuery();
        assertTrue(q.contains("response_type=code"));
        assertTrue(q.contains("client_id=app-id"));
        assertTrue(q.contains("redirect_uri=https://localhost/connect/facebook/callback"));
        assertTrue(q.contains("state=1.abc"));
        assertTrue(q.contains("scope=pages_show_list,pages_manage_posts,pages_read_engagement"));
    }

    @Test
    void 認可コードをユーザートークンに交換する() throws IOException {
        FacebookApiClient client = client(200, "{\"access_token\":\"USER-SHORT\",\"token_type\":\"bearer\",\"expires_in\":3600}");

        assertEquals("USER-SHORT", client.exchangeCode("app-id", "app-secret", "the-code", "https://l/cb"));
        assertEquals("GET", method.get());
        assertEquals("/oauth/access_token", path.get());
        assertTrue(query.get().contains("client_id=app-id"));
        assertTrue(query.get().contains("client_secret=app-secret"));
        assertTrue(query.get().contains("code=the-code"));
        assertTrue(query.get().contains("redirect_uri=https://l/cb") || query.get().contains("redirect_uri=https%3A%2F%2Fl%2Fcb"));
    }

    @Test
    void コード交換でトークンが返らなければ失敗する() throws IOException {
        FacebookApiClient noToken = client(200, "{\"token_type\":\"bearer\"}");
        assertThrows(FacebookApiException.class, () -> noToken.exchangeCode("a", "s", "c", "r"));
        server.stop(0);
        FacebookApiClient empty = client(200, "");
        assertThrows(FacebookApiException.class, () -> empty.exchangeCode("a", "s", "c", "r"));
    }

    @Test
    void ユーザートークンを長期トークンに交換する() throws IOException {
        FacebookApiClient client = client(200, "{\"access_token\":\"USER-LONG\",\"expires_in\":5184000}");

        assertEquals("USER-LONG", client.exchangeLongLived("app-id", "app-secret", "USER-SHORT"));
        assertEquals("/oauth/access_token", path.get());
        assertTrue(query.get().contains("grant_type=fb_exchange_token"));
        assertTrue(query.get().contains("client_id=app-id"));
        assertTrue(query.get().contains("client_secret=app-secret"));
        assertTrue(query.get().contains("fb_exchange_token=USER-SHORT"));
    }

    @Test
    void 長期トークンが返らなければ失敗する() throws IOException {
        FacebookApiClient client = client(200, "{\"expires_in\":5184000}");
        assertThrows(FacebookApiException.class, () -> client.exchangeLongLived("i", "s", "SHORT"));
    }

    @Test
    void 管理しているページをBearerで取得し_ページのトークンも受け取る() throws IOException {
        FacebookApiClient client = client(200, "{\"data\":[{\"id\":\"100\",\"name\":\"ページA\",\"access_token\":\"PAGE-A\"},"
                + "{\"id\":\"200\",\"name\":\"ページB\",\"access_token\":\"PAGE-B\"},{\"id\":\"300\",\"name\":\"トークンなし\"}]}");

        List<FacebookApiClient.Page> pages = client.listPages("USER-LONG");

        assertEquals("GET", method.get());
        assertEquals("/me/accounts", path.get());
        assertTrue(query.get().contains("fields=id,name,access_token"));
        assertEquals("Bearer USER-LONG", auth.get());
        // トークンを持たないページは投稿先にできないので除く。
        assertEquals(2, pages.size());
        assertEquals("100", pages.get(0).id());
        assertEquals("ページA", pages.get(0).name());
        assertEquals("PAGE-A", pages.get(0).accessToken());
    }

    @Test
    void ページが無ければ空のリストを返す() throws IOException {
        assertTrue(client(200, "{\"data\":[]}").listPages("T").isEmpty());
        server.stop(0);
        assertTrue(client(200, "{}").listPages("T").isEmpty());
        server.stop(0);
        assertTrue(client(200, "").listPages("T").isEmpty());
    }

    @Test
    void IDが無いページは除き_名前が無いか空のページはIDを名前として使う() throws IOException {
        FacebookApiClient client = client(200, "{\"data\":[{\"name\":\"IDなし\",\"access_token\":\"T0\"},"
                + "{\"id\":\"1\",\"access_token\":\"T1\"},{\"id\":\"2\",\"name\":\"\",\"access_token\":\"T2\"}]}");

        List<FacebookApiClient.Page> pages = client.listPages("T");

        assertEquals(2, pages.size());
        assertEquals("1", pages.get(0).name());
        assertEquals("2", pages.get(1).name());
    }

    @Test
    void 拒否の理由が空文字なら理由は付けない() throws IOException {
        FacebookApiClient client = client(403, "{\"error\":{\"message\":\"\"}}");

        assertEquals("Facebook のページ一覧の取得に失敗しました(HTTP 403)",
                assertThrows(FacebookApiException.class, () -> client.listPages("T")).getMessage());
    }

    @Test
    void 拒否されたら理由を含め_秘密とトークンは含めない() throws IOException {
        FacebookApiClient client = client(400,
                "{\"error\":{\"message\":\"Invalid verification code format.\",\"type\":\"OAuthException\",\"code\":100}}");

        FacebookApiException e = assertThrows(FacebookApiException.class,
                () -> client.exchangeCode("app-id", "secret-value", "the-code", "r"));

        assertTrue(e.getMessage().contains("HTTP 400"));
        assertTrue(e.getMessage().contains("Invalid verification code format."));
        assertFalse(e.getMessage().contains("secret-value"));
        assertFalse(e.getMessage().contains("the-code"));
    }

    @Test
    void 拒否の本文がJSONでなくても空でも失敗として扱う() throws IOException {
        FacebookApiClient plain = client(500, "boom");
        assertTrue(assertThrows(FacebookApiException.class, () -> plain.listPages("T")).getMessage().contains("HTTP 500"));
        server.stop(0);
        FacebookApiClient empty = client(401, "");
        assertEquals("Facebook のページ一覧の取得に失敗しました(HTTP 401)",
                assertThrows(FacebookApiException.class, () -> empty.listPages("T")).getMessage());
    }

    @Test
    void 長い理由は200文字に切る() throws IOException {
        FacebookApiClient client = client(403, "{\"error\":{\"message\":\"" + "d".repeat(500) + "\"}}");

        FacebookApiException e = assertThrows(FacebookApiException.class, () -> client.listPages("T"));

        assertTrue(e.getMessage().contains("d".repeat(200)));
        assertFalse(e.getMessage().contains("d".repeat(201)));
    }

    @Test
    void 接続できなければ失敗し_メッセージにも原因にも秘密を含めない() {
        FacebookApiClient client = new FacebookApiClient(RestClient.builder(), "http://localhost:1", "https://f.example/a");

        FacebookApiException e1 = assertThrows(FacebookApiException.class, () -> client.listPages("T"));
        FacebookApiException e2 = assertThrows(FacebookApiException.class, () -> client.exchangeCode("a", "s", "c", "r"));
        FacebookApiException e3 = assertThrows(FacebookApiException.class, () -> client.exchangeLongLived("a", "secret-value", "SHORT-TOKEN"));

        assertTrue(e1.getMessage().contains("接続できません"));
        assertTrue(e2.getMessage().contains("接続できません"));
        assertTrue(e3.getMessage().contains("接続できません"));
        assertFalse(e3.getMessage().contains("secret-value"));
        assertFalse(e3.getMessage().contains("SHORT-TOKEN"));
        assertFalse(String.valueOf(e3.getCause()).contains("secret-value"), "原因の例外を保持すると URL の秘密がログに出る");
    }

    @Test
    void ページのトークンの値は文字列表現に出ない() {
        assertFalse(new FacebookApiClient.Page("1", "名前", "PAGE-TOKEN").toString().contains("PAGE-TOKEN"));
    }
}
