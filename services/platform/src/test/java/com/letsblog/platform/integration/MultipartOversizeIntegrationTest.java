package com.letsblog.platform.integration;

import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.platform.service.BackupService;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * platform-serviceが、multipartの上限を超えるアップロードに対して<b>413 Payload Too Large</b>と
 * 上限値を含むメッセージを返すことの回帰テスト(issue #1061)。
 *
 * <p>本番の上限は500MBであり、それを超えるリクエストをテストから送るのは非現実的なので、
 * 上限だけを小さく差し替えて同じ経路(Tomcatのmultipartパーサ →
 * {@code GlobalExceptionHandler})を通す。選んだ設定値そのものは
 * {@link MultipartSizeLimitIntegrationTest}が別途アサートする。
 *
 * <p>修正前は{@code GlobalExceptionHandler#handleIllegalState}がサイズ超過を一律409 CONFLICTへ
 * 写像していた(publishing-serviceと同じ構造の問題。issue #1061)。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.servlet.multipart.max-file-size=64KB",
                "spring.servlet.multipart.max-request-size=128KB"
        })
@ActiveProfiles("test")
@DisplayName("platform-service: multipart上限超過は413を返す(issue #1061)")
class MultipartOversizeIntegrationTest {

    private static final long MAX_FILE_SIZE_BYTES = 64L * 1024;
    private static final long MAX_REQUEST_SIZE_BYTES = 128L * 1024;

    @LocalServerPort
    private int port;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private BackupService backupService;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        when(jwtDecoder.decode("test-token")).thenReturn(JwtTestFixtures.jwt("sub-1061", "admin"));
    }

    @Test
    @DisplayName("max-file-sizeを超えるアーカイブは413で、応答に上限値が含まれる")
    void ファイル単体の上限超過は413() throws Exception {
        final MultipartBodyBuilder body = new MultipartBodyBuilder()
                .field("confirm", "true")
                .file("file", "lets-blog-backup.zip", "application/zip", new byte[100 * 1024]);

        final HttpResponse<String> response = post("/api/backup/restore", body);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body())
                .contains(String.valueOf(MAX_FILE_SIZE_BYTES))
                .contains(String.valueOf(MAX_REQUEST_SIZE_BYTES));
        verifyNoInteractions(backupService);
    }

    /**
     * 上限超過そのものより先に、<b>接続が切られてしまわない</b>ことの回帰テスト。
     *
     * <p>Tomcatは上限超過でリクエストを打ち切ったあと、未読のリクエストボディを読み捨ててから
     * 応答を返す。読み捨てる量の上限が{@code server.tomcat.max-swallow-size}(既定2MB)で、
     * これを超える残りがあると<b>応答を返さずにコネクションを閉じる</b>。実在するバックアップ
     * アーカイブは数十MB〜数百MBなので、既定のままでは受入基準「413が返り、応答に上限値が
     * 含まれる」が実サイズでは成立しない。
     */
    @Test
    @DisplayName("上限を大きく超えるアップロード(max-swallow-sizeの既定2MB超)でも接続が切れず413が返る")
    void 上限を大きく超えるアップロードでも413が返る() throws Exception {
        final MultipartBodyBuilder body = new MultipartBodyBuilder()
                .field("confirm", "true")
                .file("file", "lets-blog-backup.zip", "application/zip", new byte[5 * 1024 * 1024]);

        final HttpResponse<String> response = post("/api/backup/restore", body);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains(String.valueOf(MAX_FILE_SIZE_BYTES));
        verifyNoInteractions(backupService);
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
