package com.letsblog.publishing.integration;

import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.publishing.service.AdminAuthorizationService;
import com.letsblog.publishing.service.BulkManagementService;
import com.letsblog.publishing.service.CurrentActorService;
import com.letsblog.publishing.service.PostPublishService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * publishing-serviceが、multipartの上限を超えるアップロードに対して<b>413 Payload Too Large</b>と
 * 上限値を含むメッセージを返すことの回帰テスト(issue #1061)。
 *
 * <p><b>なぜ上限をテスト側で小さく差し替えるのか。</b>本番の上限は500MBであり、それを超える
 * リクエストをテストから送るのは非現実的である。ここで検証したいのは「Tomcatが投げたサイズ超過
 * 例外を{@code GlobalExceptionHandler}が413へ写像し、応答に実際の設定値が載る」という
 * <b>写像の挙動</b>なので、上限だけを小さく差し替えて同じ経路を通す。実際に選んだ設定値そのものは
 * {@link MultipartSizeLimitIntegrationTest}が別途アサートする。
 *
 * <p><b>修正前の挙動(issue #1061のコメント、2026-09-06 02:07の実測)。</b>Tomcatの
 * {@code Request.parseParts()}がサイズ超過を{@code IllegalStateException}
 * ({@code org.apache.tomcat.util.http.InvalidParameterException})でラップするため、
 * {@code GlobalExceptionHandler#handleIllegalState}が一律409 CONFLICTへ写像していた。
 * 利用者には「対象リソースの状態が競合しています」と表示され、真因(サイズ超過)と正反対の
 * 案内になっていた。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.servlet.multipart.max-file-size=64KB",
                "spring.servlet.multipart.max-request-size=128KB"
        })
@ActiveProfiles("test")
@DisplayName("publishing-service: multipart上限超過は413を返す(issue #1061)")
class MultipartOversizeIntegrationTest {

    private static final long MAX_FILE_SIZE_BYTES = 64L * 1024;
    private static final long MAX_REQUEST_SIZE_BYTES = 128L * 1024;

    @LocalServerPort
    private int port;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private PostPublishService postPublishService;

    @MockitoBean
    private BulkManagementService bulkManagementService;

    @MockitoBean
    private AdminAuthorizationService adminAuthorizationService;

    @MockitoBean
    private CurrentActorService currentActorService;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        when(jwtDecoder.decode("test-token")).thenReturn(JwtTestFixtures.jwt("sub-1061", "admin"));
    }

    @Test
    @DisplayName("max-file-sizeを超える単一ファイルは413で、応答に上限値が含まれる")
    void ファイル単体の上限超過は413() throws Exception {
        final MultipartBodyBuilder body = new MultipartBodyBuilder()
                .field("operationType", "PLUGIN_INSTALL")
                .file("file", "plugin.zip", "application/zip", new byte[100 * 1024]);

        final HttpResponse<String> response = post("/api/projects/1/bulk-management/upload", body);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body())
                .contains(String.valueOf(MAX_FILE_SIZE_BYTES))
                .contains(String.valueOf(MAX_REQUEST_SIZE_BYTES));
        verifyNoInteractions(bulkManagementService);
    }

    @Test
    @DisplayName("max-request-sizeを超えるリクエスト全体も413で、応答に上限値が含まれる")
    void リクエスト全体の上限超過は413() throws Exception {
        final MultipartBodyBuilder body = new MultipartBodyBuilder()
                .field("site", "site-key")
                .field("title", "タイトル")
                .field("markdown", "本文")
                .file("images", "a.png", "image/png", new byte[60 * 1024])
                .file("images", "b.png", "image/png", new byte[60 * 1024])
                .file("images", "c.png", "image/png", new byte[60 * 1024]);

        final HttpResponse<String> response = post("/api/posts/publish", body);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body())
                .contains(String.valueOf(MAX_FILE_SIZE_BYTES))
                .contains(String.valueOf(MAX_REQUEST_SIZE_BYTES));
        verifyNoInteractions(postPublishService);
    }

    /**
     * 上限超過そのものより先に、<b>接続が切られてしまわない</b>ことの回帰テスト。
     *
     * <p>Tomcatは上限超過でリクエストを打ち切ったあと、未読のリクエストボディを読み捨ててから
     * 応答を返す。読み捨てる量の上限が{@code server.tomcat.max-swallow-size}(既定2MB)で、
     * これを超える残りがあると<b>応答を返さずにコネクションを閉じる</b>。クライアントには413ではなく
     * 接続リセット({@code java.io.IOException})が見える。実際のプラグインzip・バックアップ
     * アーカイブは数十MB〜数百MBなので、既定のままではこのIssueの受入基準
     * 「413が返り、応答に上限値が含まれる」が実サイズでは成立しない。
     */
    @Test
    @DisplayName("上限を大きく超えるアップロード(max-swallow-sizeの既定2MB超)でも接続が切れず413が返る")
    void 上限を大きく超えるアップロードでも413が返る() throws Exception {
        final MultipartBodyBuilder body = new MultipartBodyBuilder()
                .field("operationType", "PLUGIN_INSTALL")
                .file("file", "plugin.zip", "application/zip", new byte[5 * 1024 * 1024]);

        final HttpResponse<String> response = post("/api/projects/1/bulk-management/upload", body);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains(String.valueOf(MAX_FILE_SIZE_BYTES));
        verifyNoInteractions(bulkManagementService);
    }

    private HttpResponse<String> post(String path, MultipartBodyBuilder body) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer test-token")
                .header("Content-Type", body.contentType())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.build()))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
