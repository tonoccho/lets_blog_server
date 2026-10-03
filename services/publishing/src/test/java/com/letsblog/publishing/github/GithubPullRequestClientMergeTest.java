package com.letsblog.publishing.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
 * {@link GithubPullRequestClient}のマージ・ブランチ削除(issue #1343)の単体テスト。
 * {@link GithubPullRequestClientCommentTest}と同じく、JDK標準の{@link HttpServer}をGitHub API代わりにする。
 */
class GithubPullRequestClientMergeTest {

    private static final GithubAccess ACCESS = new GithubAccess("tok-1", "octo", "blog");

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("マージはpulls/{n}/mergeへPUTし、認証ヘッダーを付ける")
    void merge_puts() throws IOException {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> uri = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        server = start(exchange -> {
            method.set(exchange.getRequestMethod());
            uri.set(exchange.getRequestURI().toString());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"merged\":true,\"sha\":\"abc\"}");
        });

        client().mergePullRequest(ACCESS, 42);

        assertThat(method.get()).isEqualTo("PUT");
        assertThat(uri.get()).isEqualTo("/repos/octo/blog/pulls/42/merge");
        assertThat(auth.get()).isEqualTo("Bearer tok-1");
    }

    @Test
    @DisplayName("マージの応答がmerged=falseなら成功と見なさずマージできない例外にする")
    void merge_notMergedFlag() throws IOException {
        server = start(exchange -> respond(exchange, 200, "{\"merged\":false,\"message\":\"x\"}"));

        assertThatThrownBy(() -> client().mergePullRequest(ACCESS, 42))
                .isInstanceOf(PullRequestNotMergeableException.class);
    }

    @Test
    @DisplayName("マージの応答が空なら成功と見なさずGithubApiExceptionにする")
    void merge_emptyResponse() throws IOException {
        server = start(exchange -> respond(exchange, 200, ""));

        assertThatThrownBy(() -> client().mergePullRequest(ACCESS, 42))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("空の応答");
    }

    @Test
    @DisplayName("マージが405(コンフリクト・draft・保護規則)ならGitHubのメッセージを含むマージできない例外にする")
    void merge_405() throws IOException {
        server = start(exchange -> respond(exchange, 405, "{\"message\":\"Pull Request is not mergeable\"}"));

        assertThatThrownBy(() -> client().mergePullRequest(ACCESS, 42))
                .isInstanceOf(PullRequestNotMergeableException.class)
                .hasMessageContaining("#42")
                .hasMessageContaining("マージできません")
                .hasMessageContaining("Pull Request is not mergeable");
    }

    @Test
    @DisplayName("マージが409(headが進んだ等)でもマージできない例外にする")
    void merge_409() throws IOException {
        server = start(exchange -> respond(exchange, 409, "{\"message\":\"Head branch was modified\"}"));

        assertThatThrownBy(() -> client().mergePullRequest(ACCESS, 42))
                .isInstanceOf(PullRequestNotMergeableException.class)
                .hasMessageContaining("Head branch was modified");
    }

    @Test
    @DisplayName("マージが403なら権限不足と分かるGithubApiExceptionにする(マージできない例外にはしない)")
    void merge_403() throws IOException {
        server = start(exchange -> respond(exchange, 403, "{}"));

        assertThatThrownBy(() -> client().mergePullRequest(ACCESS, 42))
                .isInstanceOf(GithubApiException.class)
                .isNotInstanceOf(PullRequestNotMergeableException.class)
                .hasMessageContaining("権限");
    }

    @Test
    @DisplayName("マージが404ならPull Requestが見つからないと報告する")
    void merge_404() throws IOException {
        server = start(exchange -> respond(exchange, 404, "{}"));

        assertThatThrownBy(() -> client().mergePullRequest(ACCESS, 42))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("Pull Request #42 が見つかりません: octo/blog");
    }

    @Test
    @DisplayName("マージへ接続できなければGithubApiExceptionにする")
    void merge_connectionFailure() throws IOException {
        server = start(exchange -> respond(exchange, 200, "{}"));
        GithubPullRequestClient client = client();
        server.stop(0);
        server = null;

        assertThatThrownBy(() -> client.mergePullRequest(ACCESS, 42))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("GitHub API呼び出しに失敗しました");
    }

    @Test
    @DisplayName("ブランチ削除はgit/refs/heads/{branch}へDELETEし、スラッシュを含むブランチ名もそのまま渡す")
    void deleteBranch_deletes() throws IOException {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> uri = new AtomicReference<>();
        server = start(exchange -> {
            method.set(exchange.getRequestMethod());
            uri.set(exchange.getRequestURI().toString());
            respond(exchange, 204, "");
        });

        assertThatCode(() -> client().deleteBranch(ACCESS, "article/sample")).doesNotThrowAnyException();

        assertThat(method.get()).isEqualTo("DELETE");
        assertThat(uri.get()).isEqualTo("/repos/octo/blog/git/refs/heads/article/sample");
    }

    @Test
    @DisplayName("ブランチ削除が422(参照が無い)ならGithubApiExceptionにする")
    void deleteBranch_422() throws IOException {
        server = start(exchange -> respond(exchange, 422, "{\"message\":\"Reference does not exist\"}"));

        assertThatThrownBy(() -> client().deleteBranch(ACCESS, "article/sample"))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("Reference does not exist");
    }

    @Test
    @DisplayName("ブランチ削除が403なら権限不足と分かるGithubApiExceptionにする")
    void deleteBranch_403() throws IOException {
        server = start(exchange -> respond(exchange, 403, "{}"));

        assertThatThrownBy(() -> client().deleteBranch(ACCESS, "article/sample"))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("権限");
    }

    @Test
    @DisplayName("ブランチ削除へ接続できなければGithubApiExceptionにする")
    void deleteBranch_connectionFailure() throws IOException {
        server = start(exchange -> respond(exchange, 204, ""));
        GithubPullRequestClient client = client();
        server.stop(0);
        server = null;

        assertThatThrownBy(() -> client.deleteBranch(ACCESS, "article/sample"))
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
