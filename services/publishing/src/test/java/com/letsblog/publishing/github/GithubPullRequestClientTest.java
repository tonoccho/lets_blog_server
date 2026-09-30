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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * {@link GithubPullRequestClient}の単体テスト(issue #1337)。JDK標準の{@link HttpServer}をGitHub API代わりに
 * 待ち受け、実際のHTTP往復で検証する(ContentServiceClientTestと同じ手法)。
 *
 * <p>特に403は、権限不足とレート制限を別のメッセージにすることを固定する(ai-serviceの
 * GithubClientは403を汎用メッセージへ落とし、利用者に原因が伝わらなかった)。
 */
class GithubPullRequestClientTest {

    private static final GithubAccess ACCESS = new GithubAccess("tok-1", "octo", "blog");

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("開いているPR一覧を番号・タイトル・headブランチ名・作成日時・URLへ写し、per_page=100で要求する")
    void listOpenPullRequests_mapsFields() throws IOException {
        AtomicReference<String> uri = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        server = start(exchange -> {
            uri.set(exchange.getRequestURI().toString());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, Map.of(), """
                    [{"number":201,"title":"記事A","html_url":"https://github.com/octo/blog/pull/201",
                      "created_at":"2026-09-01T00:00:00Z","head":{"ref":"article/a","sha":"abc"}},
                     {"number":202,"title":"記事B","html_url":"https://github.com/octo/blog/pull/202",
                      "created_at":"2026-09-02T00:00:00Z","head":{"ref":"article/b","sha":"def"}}]
                    """);
        });

        List<GithubPullRequestSummary> result = client().listOpenPullRequests(ACCESS);

        assertThat(uri.get()).isEqualTo("/repos/octo/blog/pulls?state=open&per_page=100");
        assertThat(auth.get()).isEqualTo("Bearer tok-1");
        assertThat(result).containsExactly(
                new GithubPullRequestSummary(
                        201, "記事A", "article/a", "2026-09-01T00:00:00Z", "https://github.com/octo/blog/pull/201"),
                new GithubPullRequestSummary(
                        202, "記事B", "article/b", "2026-09-02T00:00:00Z", "https://github.com/octo/blog/pull/202"));
    }

    @Test
    @DisplayName("応答が空・配列でないときは空リストを返す")
    void listOpenPullRequests_emptyOrNonArray() throws IOException {
        server = start(exchange -> respond(exchange, 200, Map.of(), "{}"));
        assertThat(client().listOpenPullRequests(ACCESS)).isEmpty();
        server.stop(0);

        server = start(exchange -> respond(exchange, 200, Map.of(), ""));
        assertThat(client().listOpenPullRequests(ACCESS)).isEmpty();
    }

    @Test
    @DisplayName("欠けたフィールド(head・created_at)があっても落ちずに空文字で返す")
    void listOpenPullRequests_missingFields() throws IOException {
        server = start(exchange -> respond(exchange, 200, Map.of(), "[{\"number\":5}]"));

        assertThat(client().listOpenPullRequests(ACCESS))
                .containsExactly(new GithubPullRequestSummary(5, "", "", "", ""));
    }

    @Test
    @DisplayName("401は認証失敗として報告する")
    void unauthorized() throws IOException {
        server = start(exchange -> respond(exchange, 401, Map.of(), "{\"message\":\"Bad credentials\"}"));

        assertThatThrownBy(() -> client().listOpenPullRequests(ACCESS))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("認証に失敗");
    }

    @Test
    @DisplayName("403は権限不足として報告し、汎用メッセージへ落とさない")
    void forbiddenIsInsufficientPermission() throws IOException {
        server = start(exchange -> respond(exchange, 403, Map.of(), "{\"message\":\"Resource not accessible\"}"));

        assertThatThrownBy(() -> client().listOpenPullRequests(ACCESS))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("権限が不足")
                .hasMessageContaining("Pull requests");
    }

    @Test
    @DisplayName("403でもX-RateLimit-Remainingが0ならレート制限として報告する")
    void forbiddenWithExhaustedRateLimit() throws IOException {
        server = start(exchange ->
                respond(exchange, 403, Map.of("X-RateLimit-Remaining", "0"), "{\"message\":\"rate limit\"}"));

        assertThatThrownBy(() -> client().listOpenPullRequests(ACCESS))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("レート制限");
    }

    @Test
    @DisplayName("X-RateLimit-Remainingが0以外の403は権限不足のまま")
    void forbiddenWithRemainingRateLimit() throws IOException {
        server = start(exchange ->
                respond(exchange, 403, Map.of("X-RateLimit-Remaining", "42"), "{}"));

        assertThatThrownBy(() -> client().listOpenPullRequests(ACCESS))
                .hasMessageContaining("権限が不足");
    }

    @Test
    @DisplayName("404はリポジトリが見つからないとして報告する")
    void notFoundRepository() throws IOException {
        server = start(exchange -> respond(exchange, 404, Map.of(), "{}"));

        assertThatThrownBy(() -> client().listOpenPullRequests(ACCESS))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("リポジトリが見つかりません: octo/blog");
    }

    @Test
    @DisplayName("その他の失敗はステータスと本文を含めて報告する")
    void otherFailure() throws IOException {
        server = start(exchange -> respond(exchange, 500, Map.of(), "boom"));

        assertThatThrownBy(() -> client().listOpenPullRequests(ACCESS))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("500")
                .hasMessageContaining("boom");
    }

    @Test
    @DisplayName("接続できないときはGithubApiExceptionにする")
    void connectionFailure() throws IOException {
        server = start(exchange -> respond(exchange, 200, Map.of(), "[]"));
        GithubPullRequestClient client = client();
        server.stop(0);
        server = null;

        assertThatThrownBy(() -> client.listOpenPullRequests(ACCESS))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("GitHub API呼び出しに失敗しました");
    }

    @Test
    @DisplayName("PR詳細からhead.sha・head.ref・mergeable・mergedを読む")
    void getPullRequest_mapsFields() throws IOException {
        AtomicReference<String> uri = new AtomicReference<>();
        server = start(exchange -> {
            uri.set(exchange.getRequestURI().toString());
            respond(exchange, 200, Map.of(), """
                    {"number":201,"head":{"ref":"article/a","sha":"abc123"},"mergeable":true,"merged":false}
                    """);
        });

        GithubPullRequestDetail detail = client().getPullRequest(ACCESS, 201);

        assertThat(uri.get()).isEqualTo("/repos/octo/blog/pulls/201");
        assertThat(detail).isEqualTo(new GithubPullRequestDetail(201, "abc123", "article/a", Boolean.TRUE, false));
    }

    @Test
    @DisplayName("mergeableがnull(GitHubが計算中)ならnullのまま、mergedがtrueならtrue")
    void getPullRequest_mergeableNull() throws IOException {
        server = start(exchange -> respond(exchange, 200, Map.of(), """
                {"number":9,"head":{"ref":"x","sha":"y"},"mergeable":null,"merged":true}
                """));

        GithubPullRequestDetail detail = client().getPullRequest(ACCESS, 9);

        assertThat(detail.mergeable()).isNull();
        assertThat(detail.merged()).isTrue();
    }

    @Test
    @DisplayName("PR詳細の欠けたフィールドは空文字・falseで返す")
    void getPullRequest_missingFields() throws IOException {
        server = start(exchange -> respond(exchange, 200, Map.of(), "{}"));

        assertThat(client().getPullRequest(ACCESS, 3))
                .isEqualTo(new GithubPullRequestDetail(3, "", "", null, false));
    }

    @Test
    @DisplayName("PR詳細の応答が空ならGithubApiException")
    void getPullRequest_emptyBody() throws IOException {
        server = start(exchange -> respond(exchange, 200, Map.of(), ""));

        assertThatThrownBy(() -> client().getPullRequest(ACCESS, 3))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("空の応答");
    }

    @Test
    @DisplayName("存在しないPR詳細の404はPull Requestが見つからないとして報告する")
    void getPullRequest_notFound() throws IOException {
        server = start(exchange -> respond(exchange, 404, Map.of(), "{}"));

        assertThatThrownBy(() -> client().getPullRequest(ACCESS, 77))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("Pull Request #77 が見つかりません");
    }

    @Test
    @DisplayName("リポジトリのdefault_branchを返す")
    void getDefaultBranch() throws IOException {
        AtomicReference<String> uri = new AtomicReference<>();
        server = start(exchange -> {
            uri.set(exchange.getRequestURI().toString());
            respond(exchange, 200, Map.of(), "{\"name\":\"blog\",\"default_branch\":\"develop\"}");
        });

        assertThat(client().getDefaultBranch(ACCESS)).isEqualTo("develop");
        assertThat(uri.get()).isEqualTo("/repos/octo/blog");
    }

    @Test
    @DisplayName("default_branchが読めないときはGithubApiException")
    void getDefaultBranch_missing() throws IOException {
        server = start(exchange -> respond(exchange, 200, Map.of(), "{}"));

        assertThatThrownBy(() -> client().getDefaultBranch(ACCESS))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("default_branch");
    }

    @Test
    @DisplayName("default_branch取得の403も権限不足として報告する")
    void getDefaultBranch_forbidden() throws IOException {
        server = start(exchange -> respond(exchange, 403, Map.of(), "{}"));

        assertThatThrownBy(() -> client().getDefaultBranch(ACCESS))
                .hasMessageContaining("権限が不足");
    }

    @Test
    @DisplayName("GithubApiExceptionは原因を持つ形でも生成できる")
    void exceptionWithCause() {
        RuntimeException cause = new RuntimeException("x");
        assertThat(new GithubApiException("m", cause).getCause()).isSameAs(cause);
        assertThat(new GithubApiException("m").getMessage()).isEqualTo("m");
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

    private static void respond(HttpExchange exchange, int status, Map<String, String> headers, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
        exchange.close();
    }
}
