package com.letsblog.publishing.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.letsblog.publishing.client.ProjectServiceClient.GithubAccess;
import com.letsblog.publishing.service.ProjectNotFoundException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;

/**
 * {@link ProjectServiceClient#resolveGithubAccess}の単体テスト(issue #1337)。project-serviceの既存ブリッジ
 * {@code GET /api/internal/project/projects/{id}/github-access?actorUserId=}を呼ぶだけで、
 * トークン解決規則は持たない。リポジトリ・トークン未設定の409は原因の分かるメッセージのまま伝える。
 */
class ProjectServiceClientGithubAccessTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("内部ブリッジを呼び、Authorizationを転送してアクセス情報を受け取る")
    void resolvesAccess() throws IOException {
        AtomicReference<String> uri = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        server = start(exchange -> {
            uri.set(exchange.getRequestURI().toString());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"token\":\"t\",\"owner\":\"octo\",\"repo\":\"blog\"}");
        });

        GithubAccess access = client("Bearer caller").resolveGithubAccess(7L, 3L);

        assertThat(uri.get()).isEqualTo("/api/internal/project/projects/7/github-access?actorUserId=3");
        assertThat(auth.get()).isEqualTo("Bearer caller");
        assertThat(access).isEqualTo(new GithubAccess("t", "octo", "blog"));
    }

    @Test
    @DisplayName("409(リポジトリ/トークン未設定)は本文のerrorをそのままメッセージにする")
    void conflictKeepsMessage() throws IOException {
        server = start(exchange -> respond(exchange, 409,
                "{\"error\":\"このプロジェクトにGitHubリポジトリが紐付けられていません。\"}"));

        assertThatThrownBy(() -> client("Bearer caller").resolveGithubAccess(7L, 3L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("このプロジェクトにGitHubリポジトリが紐付けられていません。");
    }

    @Test
    @DisplayName("409の本文がJSONでない/errorが無いときは本文をそのまま使う")
    void conflictWithNonJsonBody() throws IOException {
        server = start(exchange -> respond(exchange, 409, "未設定です"));
        assertThatThrownBy(() -> client("Bearer caller").resolveGithubAccess(7L, 3L))
                .hasMessage("未設定です");
        server.stop(0);

        server = start(exchange -> respond(exchange, 409, "{\"other\":1}"));
        assertThatThrownBy(() -> client("Bearer caller").resolveGithubAccess(7L, 3L))
                .hasMessage("{\"other\":1}");
    }

    @Test
    @DisplayName("409の本文が空ならHTTPエラーのメッセージへ落とす")
    void conflictWithBlankBody() throws IOException {
        server = start(exchange -> respond(exchange, 409, ""));

        assertThatThrownBy(() -> client("Bearer caller").resolveGithubAccess(7L, 3L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("409");
    }

    @Test
    @DisplayName("404は未登録プロジェクトとして扱う")
    void notFound() throws IOException {
        server = start(exchange -> respond(exchange, 404, "{}"));

        assertThatThrownBy(() -> client("Bearer caller").resolveGithubAccess(7L, 3L))
                .isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    @DisplayName("その他のHTTPエラーはproject-service呼び出しの失敗として報告する")
    void otherError() throws IOException {
        server = start(exchange -> respond(exchange, 500, "boom"));

        assertThatThrownBy(() -> client("Bearer caller").resolveGithubAccess(7L, 3L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("github-access呼び出しに失敗しました")
                .hasMessageContaining("boom");
    }

    @Test
    @DisplayName("空の応答は失敗として報告する")
    void emptyBody() throws IOException {
        server = start(exchange -> respond(exchange, 200, ""));

        assertThatThrownBy(() -> client("Bearer caller").resolveGithubAccess(7L, 3L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("空の応答");
    }

    @Test
    @DisplayName("接続できないときはproject-service呼び出しの失敗として報告する")
    void connectionFailure() throws IOException {
        server = start(exchange -> respond(exchange, 200, "{}"));
        ProjectServiceClient client = client("Bearer caller");
        server.stop(0);
        server = null;

        assertThatThrownBy(() -> client.resolveGithubAccess(7L, 3L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("github-access呼び出しに失敗しました");
    }

    @Test
    @DisplayName("Authorizationが無い呼び出しでもヘッダー無しで送れる")
    void withoutAuthorization() throws IOException {
        AtomicReference<String> auth = new AtomicReference<>("unset");
        server = start(exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"token\":\"t\",\"owner\":\"o\",\"repo\":\"r\"}");
        });

        client(null).resolveGithubAccess(1L, 1L);

        assertThat(auth.get()).isNull();
    }

    private ProjectServiceClient client(String authorization) {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        if (authorization != null) {
            servletRequest.addHeader("Authorization", authorization);
        }
        return new ProjectServiceClient(
                RestClient.builder(), "http://localhost:" + server.getAddress().getPort(), servletRequest);
    }

    private static HttpServer start(HttpHandler handler) throws IOException {
        HttpServer created = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        created.createContext("/", handler);
        created.start();
        return created;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
        exchange.close();
    }
}
