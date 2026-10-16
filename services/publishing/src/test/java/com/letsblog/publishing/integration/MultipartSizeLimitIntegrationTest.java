package com.letsblog.publishing.integration;

import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.publishing.testsupport.PublishingWorld;
import com.letsblog.publishing.testsupport.PublishingWorld.SiteSpec;
import com.letsblog.publishing.testsupport.SpringWorld;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * publishing-serviceのmultipartエンドポイントが、Spring Bootの既定上限(1ファイル1MB /
 * リクエスト全体10MB)を超えるアップロードを受け取れることの回帰テスト(issue #1061)。
 *
 * <p><b>なぜ実サーブレットコンテナなのか。</b>{@code MockMvc}の{@code multipart()}は
 * {@code MockMultipartHttpServletRequest}をパース済みの状態で組み立てるため、Tomcatの
 * multipartパーサ(=上限が適用される場所)を一切通らない。上限そのものを検証するには
 * {@code webEnvironment = RANDOM_PORT}で実際にHTTPを流す必要がある。
 *
 * <p><b>なぜGherkin(受入テスト)ではないのか。</b>{@code POST /api/posts/publish}は
 * VSCode拡張専用のエンドポイントでweb UIからは到達できない。
 * {@code POST /api/projects/{id}/bulk-management/upload}はweb UIから到達できるが、
 * 実際にプロビジョニング済みのWordPress環境へプラグインをインストールしてしまうため、
 * 受入テスト環境に実サイトを用意しない限り検証できない。CLAUDE.md
 * (Test-First Implementation → Where the tests live)が認める「web UIから到達できない
 * criterionはサービス単体テストで押さえる」明示的な例外として、ここで押さえる。
 *
 * <p>issue #1816で書き直し: PostPublishService・BulkManagementService・AdminAuthorizationService・
 * CurrentActorServiceは実物のBeanを使い、外部との通信(identity・project・content・media-serviceとprovision-agentへの
 * HTTPは{@code FakeHttpServer}、WordPressへのSSHは{@code FakeWordPress}、MQは{@code RabbitTemplate}、Keycloakの鍵取得を
 * 伴う{@code JwtDecoder})だけを差し替える。受け取ったバイト数は、外部へ実際に送られた内容で確かめる。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(SpringWorld.SshBoundary.class)
@DisplayName("publishing-service: multipartアップロードの上限(issue #1061)")
class MultipartSizeLimitIntegrationTest {

    /** Spring Bootの既定 max-file-size(1MB)を超える画像。 */
    private static final int IMAGE_BYTES = 2 * 1024 * 1024;
    /** Spring Bootの既定 max-request-size(10MB)を超えるプラグインzip。 */
    private static final int ZIP_BYTES = 12 * 1024 * 1024;

    private static final PublishingWorld WORLD = SpringWorld.start();

    @DynamicPropertySource
    static void externalServices(DynamicPropertyRegistry registry) {
        SpringWorld.register(registry, WORLD);
    }

    @AfterAll
    static void closeWorld() {
        WORLD.close();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private MultipartProperties multipartProperties;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    /** MQ(RabbitMQ)との通信そのもの。テスト環境にブローカーは無いので差し替える。 */
    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        when(jwtDecoder.decode("test-token")).thenReturn(JwtTestFixtures.jwt("sub-1061", "admin"));
        WORLD.stubActor("Bearer test-token", 1L, "admin", "admin@example.com");
    }

    @Test
    @DisplayName("1MBを超える画像を含む記事の公開(POST /api/posts/publish)が成功する")
    void 記事公開は1mbを超える画像を受け取れる() throws Exception {
        // 受け取った画像は、WordPress(SSHのフェイク)へ実際にアップロードされたバイト数で確かめる。
        WORLD.stubSite(1L, "site-key", false).stubCredentials("site-key", PublishingWorld.sshCredentialsMap());
        WORLD.project.json("GET", "/api/internal/project/sites/1/project-id", "{\"projectId\":null}");

        final MultipartBodyBuilder body = new MultipartBodyBuilder()
                .field("site", "site-key")
                .field("title", "タイトル")
                .field("markdown", "![](assets/eyecatch.png)")
                .file("images", "eyecatch.png", "image/png", filler(IMAGE_BYTES));

        final HttpResponse<String> response = post("/api/posts/publish", body);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(WORLD.ssh.media.values()).hasSize(1);
        assertThat(WORLD.ssh.media.values().iterator().next().bytes).hasSize(IMAGE_BYTES);
    }

    @Test
    @DisplayName("既定のmax-request-size(10MB)を超えるプラグインzipのアップロードが成功する")
    void 一括管理のzipアップロードは10mbを超えるファイルを受け取れる() throws Exception {
        // 受け取ったzipは、provision-agentへ実際に送られたリクエストの大きさで確かめる。
        WORLD.agent.json("POST", "/bulk-management/upload", "{\"status\":\"success\"}");
        WORLD.bindEnvironments(1L, "test", SiteSpec.managed(10L, "local-site"), null, null);

        final MultipartBodyBuilder body = new MultipartBodyBuilder()
                .field("operationType", "PLUGIN_INSTALL")
                .file("file", "plugin.zip", "application/zip", filler(ZIP_BYTES));

        final HttpResponse<String> response = post("/api/projects/1/bulk-management/upload", body);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(WORLD.agent.requests("POST", "/bulk-management/upload")).hasSize(1);
        assertThat(WORLD.agent.requests("POST", "/bulk-management/upload").get(0).body().length)
                .isGreaterThanOrEqualTo(ZIP_BYTES);
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
