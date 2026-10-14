package com.letsblog.ai.client;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * issue #1771: project-service の github-access が返す 409 の {@code details.reason} を読み、
 * トークン未設定だけを専用の例外にする。日本語の文言は照合しない。実HTTPサーバーに対して検証する
 * (ProjectBridgeClient は独自の requestFactory を組み立てるため、MockRestServiceServer は使えない)。
 */
class ProjectBridgeClientGithubAccessTest {

    private HttpServer server;
    private volatile int status = 200;
    private volatile String body = "";
    private ProjectBridgeClient client;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/project/projects/1/github-access", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        client = new ProjectBridgeClient(
                RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void respond(HttpStatus httpStatus, String responseBody) {
        this.status = httpStatus.value();
        this.body = responseBody;
    }

    @Test
    void 成功時はアクセス情報を返す() {
        respond(HttpStatus.OK, "{\"token\":\"t\",\"owner\":\"o\",\"repo\":\"r\"}");

        ProjectBridgeClient.GithubAccess access = client.resolveGithubAccess(1L, 2L, "Bearer x");

        assertEquals(new ProjectBridgeClient.GithubAccess("t", "o", "r"), access);
    }

    @Test
    void 設定不備の409はdetailsのreasonで専用例外になり本文がそのままメッセージになる() {
        String body = "{\"error\":\"ユーザーの GitHub トークンが設定されていません\","
                + "\"details\":{\"reason\":\"GITHUB_TOKEN_NOT_CONFIGURED\"}}";
        respond(HttpStatus.CONFLICT, body);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> client.resolveGithubAccess(1L, 2L, "Bearer x"));

        assertInstanceOf(GithubTokenNotConfiguredException.class, e);
        assertEquals(body, e.getMessage());
        assertEquals("GITHUB_TOKEN_NOT_CONFIGURED", GithubTokenNotConfiguredException.REASON);
    }

    @Test
    void reasonの無い409は従来どおりの例外で文言が似ていても専用例外にしない() {
        respond(HttpStatus.CONFLICT, "{\"error\":\"ユーザーの GitHub トークンが設定されていません\"}");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> client.resolveGithubAccess(1L, 2L, "Bearer x"));

        assertFalse(e instanceof GithubTokenNotConfiguredException);
    }

    @Test
    void 別のreasonの409は専用例外にしない() {
        respond(HttpStatus.CONFLICT, "{\"error\":\"x\",\"details\":{\"reason\":\"OTHER\"}}");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> client.resolveGithubAccess(1L, 2L, "Bearer x"));

        assertFalse(e instanceof GithubTokenNotConfiguredException);
    }

    @Test
    void detailsがオブジェクトでない409は専用例外にしない() {
        respond(HttpStatus.CONFLICT, "{\"error\":\"x\",\"details\":\"GITHUB_TOKEN_NOT_CONFIGURED\"}");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> client.resolveGithubAccess(1L, 2L, "Bearer x"));

        assertFalse(e instanceof GithubTokenNotConfiguredException);
    }

    @Test
    void JSONでない本文の409は従来どおりの例外になる() {
        respond(HttpStatus.CONFLICT, "not json");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> client.resolveGithubAccess(1L, 2L, "Bearer x"));

        assertFalse(e instanceof GithubTokenNotConfiguredException);
        assertEquals("not json", e.getMessage());
    }

    @Test
    void 本文が空の409は例外メッセージにステータスの文言を使う() {
        respond(HttpStatus.CONFLICT, "");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> client.resolveGithubAccess(1L, 2L, null));

        assertFalse(e instanceof GithubTokenNotConfiguredException);
    }

    @Test
    void 通信エラーはブリッジ失敗の例外になる() {
        server.stop(0);

        assertThrows(com.letsblog.ai.service.IdentityServiceUnavailableException.class,
                () -> client.resolveGithubAccess(1L, 2L, "Bearer x"));
    }
}
