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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * {@link GithubPullRequestClient}の記事提出向けメソッド(ブランチ存在確認・PR作成)の単体テスト(issue #1339)。
 * {@link GithubPullRequestClientTest}と同じく、JDK標準の{@link HttpServer}をGitHub API代わりにする。
 */
class GithubPullRequestClientSubmissionTest {

    private static final GithubAccess ACCESS = new GithubAccess("tok-1", "octo", "blog");

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("ブランチが在れば真を返し、スラッシュを含むブランチ名もそのまま問い合わせる")
    void branchExists_true() throws IOException {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> uri = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        server = start(exchange -> {
            method.set(exchange.getRequestMethod());
            uri.set(exchange.getRequestURI().getRawPath());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"name\":\"article/a\"}");
        });

        assertThat(client().branchExists(ACCESS, "article/a")).isTrue();
        assertThat(method.get()).isEqualTo("GET");
        assertThat(uri.get()).isEqualTo("/repos/octo/blog/branches/article/a");
        assertThat(auth.get()).isEqualTo("Bearer tok-1");
    }

    @Test
    @DisplayName("ブランチが無い(404)なら偽を返し、例外にしない")
    void branchExists_false() throws IOException {
        server = start(exchange -> respond(exchange, 404, "{\"message\":\"Branch not found\"}"));

        assertThat(client().branchExists(ACCESS, "article/missing")).isFalse();
    }

    @Test
    @DisplayName("ブランチ確認の認証失敗(401)は偽にせず、認証が原因と分かる例外にする")
    void branchExists_unauthorized() throws IOException {
        server = start(exchange -> respond(exchange, 401, "{}"));

        assertThatThrownBy(() -> client().branchExists(ACCESS, "article/a"))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("認証");
    }

    @Test
    @DisplayName("ブランチ確認で接続できなければGithubApiExceptionにする")
    void branchExists_unreachable() throws IOException {
        server = start(exchange -> respond(exchange, 200, "{}"));
        GithubPullRequestClient client = client();
        server.stop(0);
        server = null;

        assertThatThrownBy(() -> client.branchExists(ACCESS, "article/a"))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("GitHub API呼び出しに失敗しました");
    }

    @Test
    @DisplayName("PR作成はtitle・body・head・baseをPOSTし、作られたPRを番号・URL付きで返す")
    void createPullRequest_postsAndMaps() throws IOException {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> uri = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = start(exchange -> {
            method.set(exchange.getRequestMethod());
            uri.set(exchange.getRequestURI().toString());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 201, """
                    {"number":301,"title":"記事提出","html_url":"https://github.com/octo/blog/pull/301",
                     "created_at":"2026-09-01T00:00:00Z","head":{"ref":"article/a","sha":"abc"}}
                    """);
        });

        GithubPullRequestSummary created =
                client().createPullRequest(ACCESS, "記事提出", "Closes #12", "article/a", "develop");

        assertThat(method.get()).isEqualTo("POST");
        assertThat(uri.get()).isEqualTo("/repos/octo/blog/pulls");
        assertThat(auth.get()).isEqualTo("Bearer tok-1");
        assertThat(requestBody.get())
                .contains("\"title\":\"記事提出\"")
                .contains("\"body\":\"Closes #12\"")
                .contains("\"head\":\"article/a\"")
                .contains("\"base\":\"develop\"");
        assertThat(created.number()).isEqualTo(301);
        assertThat(created.url()).isEqualTo("https://github.com/octo/blog/pull/301");
        assertThat(created.headBranch()).isEqualTo("article/a");
    }

    @Test
    @DisplayName("PR作成が422(headが無い等)ならGitHubの拒否理由を載せたGithubApiExceptionにする")
    void createPullRequest_unprocessable() throws IOException {
        server = start(exchange -> respond(exchange, 422, "{\"message\":\"Validation Failed\"}"));

        assertThatThrownBy(() -> client().createPullRequest(ACCESS, "t", "b", "article/a", "main"))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("Pull Requestの作成")
                .hasMessageContaining("Validation Failed");
    }

    @Test
    @DisplayName("PR作成が404ならリポジトリが見つからないと報告する")
    void createPullRequest_notFound() throws IOException {
        server = start(exchange -> respond(exchange, 404, "{}"));

        assertThatThrownBy(() -> client().createPullRequest(ACCESS, "t", "b", "article/a", "main"))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("リポジトリが見つかりません: octo/blog");
    }

    @Test
    @DisplayName("PR作成の応答が空ならGithubApiExceptionにする")
    void createPullRequest_emptyResponse() throws IOException {
        server = start(exchange -> respond(exchange, 201, ""));

        assertThatThrownBy(() -> client().createPullRequest(ACCESS, "t", "b", "article/a", "main"))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("空の応答");
    }

    @Test
    @DisplayName("PR作成で接続できなければGithubApiExceptionにする")
    void createPullRequest_unreachable() throws IOException {
        server = start(exchange -> respond(exchange, 201, "{}"));
        GithubPullRequestClient client = client();
        server.stop(0);
        server = null;

        assertThatThrownBy(() -> client.createPullRequest(ACCESS, "t", "b", "article/a", "main"))
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
