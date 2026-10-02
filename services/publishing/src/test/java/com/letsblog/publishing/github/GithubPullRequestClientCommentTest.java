package com.letsblog.publishing.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.letsblog.publishing.client.ProjectServiceClient.GithubAccess;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * {@link GithubPullRequestClient}のコメント投稿・取得(issue #1344)の単体テスト。
 * {@link GithubPullRequestClientSubmissionTest}と同じく、JDK標準の{@link HttpServer}をGitHub API代わりにする。
 */
class GithubPullRequestClientCommentTest {

    private static final GithubAccess ACCESS = new GithubAccess("tok-1", "octo", "blog");

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("コメント投稿はissuesのcomments APIへ本文をPOSTし、作られたコメントのIDと本文を返す")
    void createIssueComment_postsAndMaps() throws IOException {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> uri = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = start(exchange -> {
            method.set(exchange.getRequestMethod());
            uri.set(exchange.getRequestURI().toString());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 201, "{\"id\":5001,\"body\":\"指摘\",\"created_at\":\"2026-01-01T00:00:00Z\"}");
        });

        GithubIssueComment created = client().createIssueComment(ACCESS, 42, "指摘");

        assertThat(method.get()).isEqualTo("POST");
        assertThat(uri.get()).isEqualTo("/repos/octo/blog/issues/42/comments");
        assertThat(auth.get()).isEqualTo("Bearer tok-1");
        assertThat(requestBody.get()).contains("\"body\":\"指摘\"");
        assertThat(created.id()).isEqualTo(5001L);
        assertThat(created.body()).isEqualTo("指摘");
    }

    @Test
    @DisplayName("コメント投稿が403(権限不足)なら権限不足と分かる例外にする")
    void createIssueComment_forbidden() throws IOException {
        server = start(exchange -> respond(exchange, 403, "{}"));

        assertThatThrownBy(() -> client().createIssueComment(ACCESS, 42, "x"))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("権限");
    }

    @Test
    @DisplayName("コメント投稿が404ならPull Requestが見つからないと報告する")
    void createIssueComment_notFound() throws IOException {
        server = start(exchange -> respond(exchange, 404, "{}"));

        assertThatThrownBy(() -> client().createIssueComment(ACCESS, 42, "x"))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("Pull Request #42 が見つかりません: octo/blog");
    }

    @Test
    @DisplayName("コメント投稿の応答が空ならGithubApiExceptionにする")
    void createIssueComment_emptyResponse() throws IOException {
        server = start(exchange -> respond(exchange, 201, ""));

        assertThatThrownBy(() -> client().createIssueComment(ACCESS, 42, "x"))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("空の応答");
    }

    @Test
    @DisplayName("コメント投稿の応答にIDが無ければGithubApiExceptionにする")
    void createIssueComment_noId() throws IOException {
        server = start(exchange -> respond(exchange, 201, "{\"body\":\"x\"}"));

        assertThatThrownBy(() -> client().createIssueComment(ACCESS, 42, "x"))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("コメントIDを読み取れませんでした");
    }

    @Test
    @DisplayName("コメント投稿で接続できなければGithubApiExceptionにする")
    void createIssueComment_unreachable() throws IOException {
        server = start(exchange -> respond(exchange, 201, "{}"));
        GithubPullRequestClient client = client();
        server.stop(0);
        server = null;

        assertThatThrownBy(() -> client.createIssueComment(ACCESS, 42, "x"))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("GitHub API呼び出しに失敗しました");
    }

    @Test
    @DisplayName("コメント取得はIDで引き、本文を返す")
    void findIssueComment_found() throws IOException {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> uri = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        server = start(exchange -> {
            method.set(exchange.getRequestMethod());
            uri.set(exchange.getRequestURI().toString());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"id\":5001,\"body\":\"指摘\"}");
        });

        Optional<GithubIssueComment> found = client().findIssueComment(ACCESS, 5001L);

        assertThat(method.get()).isEqualTo("GET");
        assertThat(uri.get()).isEqualTo("/repos/octo/blog/issues/comments/5001");
        assertThat(auth.get()).isEqualTo("Bearer tok-1");
        assertThat(found).hasValueSatisfying(c -> {
            assertThat(c.id()).isEqualTo(5001L);
            assertThat(c.body()).isEqualTo("指摘");
        });
    }

    @Test
    @DisplayName("コメントが消されていれば(404)空を返し、例外にしない")
    void findIssueComment_deleted() throws IOException {
        server = start(exchange -> respond(exchange, 404, "{}"));

        assertThat(client().findIssueComment(ACCESS, 5001L)).isEmpty();
    }

    @Test
    @DisplayName("コメント取得の認証失敗(401)は空にせず、認証が原因と分かる例外にする")
    void findIssueComment_unauthorized() throws IOException {
        server = start(exchange -> respond(exchange, 401, "{}"));

        assertThatThrownBy(() -> client().findIssueComment(ACCESS, 5001L))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("認証");
    }

    @Test
    @DisplayName("コメント取得の応答が空ならGithubApiExceptionにする")
    void findIssueComment_emptyResponse() throws IOException {
        server = start(exchange -> respond(exchange, 200, ""));

        assertThatThrownBy(() -> client().findIssueComment(ACCESS, 5001L))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("空の応答");
    }

    @Test
    @DisplayName("コメント取得で接続できなければGithubApiExceptionにする")
    void findIssueComment_unreachable() throws IOException {
        server = start(exchange -> respond(exchange, 200, "{}"));
        GithubPullRequestClient client = client();
        server.stop(0);
        server = null;

        assertThatThrownBy(() -> client.findIssueComment(ACCESS, 5001L))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("GitHub API呼び出しに失敗しました");
    }

    private GithubPullRequestClient client() {
        return new GithubPullRequestClient(RestClient.builder(), "http://localhost:" + server.getAddress().getPort());
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
