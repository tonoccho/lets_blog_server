package com.letsblog.platform.integration;

import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.platform.service.BackupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * platform-serviceの{@code POST /api/backup/restore}が、Spring Bootの既定上限
 * (1ファイル1MB / リクエスト全体10MB)を超えるバックアップアーカイブを受け取れることの
 * 回帰テスト(issue #1061)。
 *
 * <p>実在するバックアップアーカイブ(全サービスのMySQLスキーマ + Keycloak PostgreSQL +
 * 生成画像ファイル)が1MBを下回ることはまずない。既定のままではこの復旧経路そのものが
 * 機能していなかった。
 *
 * <p><b>なぜ実サーブレットコンテナなのか / なぜGherkinではないのか。</b>
 * {@code MockMvc}の{@code multipart()}はパース済みのリクエストを組み立てるためTomcatの
 * multipartパーサ(=上限が適用される場所)を通らない。またweb UI({@code /admin/backup})
 * からこの経路を受入テストで叩くと、<b>受入テスト環境の全DBを実際に復元(=破壊)</b>して
 * しまうため、Gherkinでは到達手段として選べない。CLAUDE.md(Test-First Implementation →
 * Where the tests live)が認める明示的な例外として、ここで押さえる。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("platform-service: バックアップ復元のmultipart上限(issue #1061)")
class MultipartSizeLimitIntegrationTest {

    /** Spring Bootの既定 max-file-size(1MB)/ max-request-size(10MB)をいずれも超えるサイズ。 */
    private static final int ARCHIVE_BYTES = 12 * 1024 * 1024;

    @LocalServerPort
    private int port;

    @Autowired
    private MultipartProperties multipartProperties;

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
    @DisplayName("既定上限を超えるバックアップアーカイブの復元が成功する")
    void バックアップ復元は10mbを超えるアーカイブを受け取れる() throws Exception {
        final AtomicLong received = new AtomicLong();
        doAnswer(invocation -> {
            final InputStream in = invocation.getArgument(0);
            received.set(in.readAllBytes().length);
            return null;
        }).when(backupService).restoreBackup(any(), anyBoolean(), anyBoolean());

        final MultipartBodyBuilder body = new MultipartBodyBuilder()
                .field("confirm", "true")
                .field("acknowledgeKeyMismatch", "false")
                .file("file", "lets-blog-backup.zip", "application/zip", filler(ARCHIVE_BYTES));

        final HttpResponse<String> response = post("/api/backup/restore", body);

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(received.get()).isEqualTo(ARCHIVE_BYTES);
    }

    /**
     * application.ymlで選んだ上限そのものの契約。ブラウザ→nginx({@code location /}の500M)→
     * web(Server Actionのbody-size-limit 500mb)→gateway(lbs-net内の直通でnginxを通らない)
     * という経路が500Mまで通すため、受け側もそれに合わせる(issue #1061 要件2)。
     */
    @Test
    @DisplayName("application.ymlのmultipart上限がnginx/Server Actionの通す500Mに揃っている")
    void multipart上限が経路の上限に揃っている() {
        assertThat(multipartProperties.getMaxFileSize().toBytes()).isEqualTo(500L * 1024 * 1024);
        assertThat(multipartProperties.getMaxRequestSize().toBytes()).isEqualTo(512L * 1024 * 1024);
        assertThat(multipartProperties.getFileSizeThreshold().toBytes()).isZero();
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

    private static byte[] filler(int size) {
        final byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (i % 251);
        }
        return bytes;
    }
}
