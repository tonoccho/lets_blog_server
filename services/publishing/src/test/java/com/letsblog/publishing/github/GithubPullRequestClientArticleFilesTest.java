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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * {@link GithubPullRequestClient}の記事取得系(PR変更ファイル一覧・ref指定のファイル取得、issue #1338)。
 * 1MBを超えるファイルはcontents APIがcontentを返さないため、blobs APIをraw指定で読む分岐を固定する。
 */
class GithubPullRequestClientArticleFilesTest {

    private static final GithubAccess ACCESS = new GithubAccess("tok-1", "octo", "blog");

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("PRの変更ファイルをパスと状態で返し、per_page=100で要求する")
    void listPullRequestFiles_mapsFields() throws IOException {
        List<String> uris = new CopyOnWriteArrayList<>();
        server = start(exchange -> {
            uris.add(exchange.getRequestURI().toString());
            respond(exchange, 200, "application/json", """
                    [{"filename":"articles/a/article.md","status":"added"},
                     {"filename":"articles/a/assets/x.png","status":"removed"},
                     {"status":"added"}]
                    """);
        });

        List<GithubChangedFile> files = client().listPullRequestFiles(ACCESS, 5);

        assertThat(uris).containsExactly("/repos/octo/blog/pulls/5/files?per_page=100&page=1");
        assertThat(files).containsExactly(
                new GithubChangedFile("articles/a/article.md", "added"),
                new GithubChangedFile("articles/a/assets/x.png", "removed"),
                new GithubChangedFile("", "added"));
    }

    @Test
    @DisplayName("100件ちょうどのページの次ページも読み、100件未満で止める")
    void listPullRequestFiles_paginates() throws IOException {
        List<String> uris = new CopyOnWriteArrayList<>();
        server = start(exchange -> {
            String uri = exchange.getRequestURI().toString();
            uris.add(uri);
            if (uri.endsWith("page=1")) {
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < 100; i++) {
                    sb.append(i == 0 ? "" : ",").append("{\"filename\":\"f").append(i).append("\",\"status\":\"added\"}");
                }
                respond(exchange, 200, "application/json", sb.append("]").toString());
            } else {
                respond(exchange, 200, "application/json", "[{\"filename\":\"last\",\"status\":\"added\"}]");
            }
        });

        List<GithubChangedFile> files = client().listPullRequestFiles(ACCESS, 5);

        assertThat(files).hasSize(101);
        assertThat(files.get(100).path()).isEqualTo("last");
        assertThat(uris).hasSize(2);
    }

    @Test
    @DisplayName("変更ファイルの応答が配列でない・空なら空リスト")
    void listPullRequestFiles_nonArray() throws IOException {
        server = start(exchange -> respond(exchange, 200, "application/json", "{}"));
        assertThat(client().listPullRequestFiles(ACCESS, 5)).isEmpty();
    }

    @Test
    @DisplayName("存在しないPRの変更ファイル一覧は404としてPRが見つからないと報告する")
    void listPullRequestFiles_notFound() throws IOException {
        server = start(exchange -> respond(exchange, 404, "application/json", "{}"));

        assertThatThrownBy(() -> client().listPullRequestFiles(ACCESS, 9))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("Pull Request #9 が見つかりません");
    }

    @Test
    @DisplayName("1MB以下のファイルはcontents APIをref指定で呼び、base64(改行入り)をデコードして返す")
    void getFileContent_small() throws IOException {
        AtomicUri seen = new AtomicUri();
        byte[] data = "こんにちは\n本文".getBytes(StandardCharsets.UTF_8);
        String b64 = Base64.getMimeEncoder(4, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(data);
        server = start(exchange -> {
            seen.set(exchange);
            respond(exchange, 200, "application/json",
                    "{\"sha\":\"s1\",\"size\":" + data.length + ",\"encoding\":\"base64\",\"content\":\""
                            + b64.replace("\n", "\\n") + "\"}");
        });

        byte[] result = client().getFileContent(ACCESS, "articles/a/assets/画像 1.png", "abc123");

        assertThat(result).isEqualTo(data);
        assertThat(seen.uri).isEqualTo(
                "/repos/octo/blog/contents/articles/a/assets/%E7%94%BB%E5%83%8F%201.png?ref=abc123");
        assertThat(seen.auth).isEqualTo("Bearer tok-1");
    }

    @Test
    @DisplayName("contentがnullの(1MB超の)ファイルはblobs APIをAccept: application/vnd.github.rawで読み、欠落なく返す")
    void getFileContent_largeUsesRawBlob() throws IOException {
        byte[] big = new byte[1024 * 1024 + 2048];
        java.util.Arrays.fill(big, (byte) 0x61);
        List<String> uris = new CopyOnWriteArrayList<>();
        List<String> accepts = new CopyOnWriteArrayList<>();
        server = start(exchange -> {
            uris.add(exchange.getRequestURI().toString());
            accepts.add(String.valueOf(exchange.getRequestHeaders().getFirst("Accept")));
            if (exchange.getRequestURI().getPath().contains("/git/blobs/")) {
                respond(exchange, 200, "application/octet-stream", big);
            } else {
                respond(exchange, 200, "application/json",
                        "{\"sha\":\"deadbeef\",\"size\":" + big.length + ",\"encoding\":\"none\",\"content\":null}");
            }
        });

        byte[] result = client().getFileContent(ACCESS, "articles/a/assets/large.png", "abc123");

        assertThat(result).hasSize(big.length).isEqualTo(big);
        assertThat(uris).containsExactly(
                "/repos/octo/blog/contents/articles/a/assets/large.png?ref=abc123",
                "/repos/octo/blog/git/blobs/deadbeef");
        assertThat(accepts.get(1)).isEqualTo("application/vnd.github.raw");
    }

    @Test
    @DisplayName("contentが空文字のときはblobs APIへ落とさず空を返す")
    void getFileContent_emptyFile() throws IOException {
        List<String> uris = new ArrayList<>();
        server = start(exchange -> {
            uris.add(exchange.getRequestURI().toString());
            respond(exchange, 200, "application/json", "{\"sha\":\"e69\",\"size\":0,\"encoding\":\"base64\",\"content\":\"\"}");
        });

        assertThat(client().getFileContent(ACCESS, "articles/a/assets/empty.txt", "abc")).isEmpty();
        assertThat(uris).hasSize(1);
    }

    @Test
    @DisplayName("contentもshaも無い応答はGithubApiException")
    void getFileContent_noContentNoSha() throws IOException {
        server = start(exchange -> respond(exchange, 200, "application/json", "{}"));

        assertThatThrownBy(() -> client().getFileContent(ACCESS, "articles/a/article.md", "abc"))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("articles/a/article.md");
    }

    @Test
    @DisplayName("contentsの応答が空ならGithubApiException")
    void getFileContent_emptyResponse() throws IOException {
        server = start(exchange -> respond(exchange, 200, "application/json", ""));

        assertThatThrownBy(() -> client().getFileContent(ACCESS, "articles/a/article.md", "abc"))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("空の応答");
    }

    @Test
    @DisplayName("blobsの応答が空ならGithubApiException")
    void getFileContent_emptyBlob() throws IOException {
        server = start(exchange -> {
            if (exchange.getRequestURI().getPath().contains("/git/blobs/")) {
                respond(exchange, 200, "application/octet-stream", new byte[0]);
            } else {
                respond(exchange, 200, "application/json", "{\"sha\":\"s\",\"content\":null}");
            }
        });

        assertThatThrownBy(() -> client().getFileContent(ACCESS, "articles/a/assets/l.png", "abc"))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("空の応答");
    }

    @Test
    @DisplayName("ファイルが無い(404)ときはパスとrefを含めて見つからないと報告する")
    void getFileContent_notFound() throws IOException {
        server = start(exchange -> respond(exchange, 404, "application/json", "{}"));

        assertThatThrownBy(() -> client().getFileContent(ACCESS, "articles/a/article.md", "abc123"))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("articles/a/article.md")
                .hasMessageContaining("abc123");
    }

    @Test
    @DisplayName("contents取得の403は権限不足として報告する")
    void getFileContent_forbidden() throws IOException {
        server = start(exchange -> respond(exchange, 403, "application/json", "{}"));

        assertThatThrownBy(() -> client().getFileContent(ACCESS, "a", "abc"))
                .hasMessageContaining("権限が不足");
    }

    @Test
    @DisplayName("変更ファイルの応答が空(本文なし)なら空リスト")
    void listPullRequestFiles_emptyBody() throws IOException {
        server = start(exchange -> respond(exchange, 200, "application/json", ""));
        assertThat(client().listPullRequestFiles(ACCESS, 5)).isEmpty();
    }

    @Test
    @DisplayName("常に100件返るPRでも辿るページ数は3000件分(30ページ)で打ち切る")
    void listPullRequestFiles_stopsAtGithubLimit() throws IOException {
        List<String> uris = new CopyOnWriteArrayList<>();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 100; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"filename\":\"f").append(i).append("\",\"status\":\"added\"}");
        }
        String page = sb.append("]").toString();
        server = start(exchange -> {
            uris.add(exchange.getRequestURI().toString());
            respond(exchange, 200, "application/json", page);
        });

        assertThat(client().listPullRequestFiles(ACCESS, 5)).hasSize(3000);
        assertThat(uris).hasSize(30);
    }

    @Test
    @DisplayName("contentが文字列でもencodingがbase64以外ならblobs APIで読む")
    void getFileContent_nonBase64EncodingUsesBlob() throws IOException {
        List<String> uris = new CopyOnWriteArrayList<>();
        server = start(exchange -> {
            uris.add(exchange.getRequestURI().getPath());
            if (exchange.getRequestURI().getPath().contains("/git/blobs/")) {
                respond(exchange, 200, "application/octet-stream", "raw".getBytes(StandardCharsets.UTF_8));
            } else {
                respond(exchange, 200, "application/json",
                        "{\"sha\":\"s9\",\"size\":3,\"encoding\":\"none\",\"content\":\"raw\"}");
            }
        });

        assertThat(client().getFileContent(ACCESS, "a/b.txt", "abc")).isEqualTo("raw".getBytes(StandardCharsets.UTF_8));
        assertThat(uris).hasSize(2);
    }

    @Test
    @DisplayName("contentが空文字でもsizeが0でなければ(切り詰められた応答)blobs APIで読み直す")
    void getFileContent_emptyContentButNonZeroSizeUsesBlob() throws IOException {
        server = start(exchange -> {
            if (exchange.getRequestURI().getPath().contains("/git/blobs/")) {
                respond(exchange, 200, "application/octet-stream", "abc".getBytes(StandardCharsets.UTF_8));
            } else {
                respond(exchange, 200, "application/json",
                        "{\"sha\":\"s9\",\"size\":3,\"encoding\":\"base64\",\"content\":\"\"}");
            }
        });

        assertThat(client().getFileContent(ACCESS, "a/b.txt", "abc")).isEqualTo("abc".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("blobs取得の404はblobが見つからないと報告する")
    void getFileContent_blobNotFound() throws IOException {
        server = start(exchange -> {
            if (exchange.getRequestURI().getPath().contains("/git/blobs/")) {
                respond(exchange, 404, "application/json", "{}");
            } else {
                respond(exchange, 200, "application/json", "{\"sha\":\"s9\",\"content\":null}");
            }
        });

        assertThatThrownBy(() -> client().getFileContent(ACCESS, "a/b.txt", "abc"))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("blobが見つかりません");
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

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        respond(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] bytes)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
        exchange.close();
    }

    private static final class AtomicUri {
        private volatile String uri;
        private volatile String auth;

        void set(HttpExchange exchange) {
            this.uri = exchange.getRequestURI().getRawPath()
                    + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
            this.auth = exchange.getRequestHeaders().getFirst("Authorization");
        }
    }
}
