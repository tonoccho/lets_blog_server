package com.letsblog.common.client;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.web.client.RestClient;

import com.letsblog.common.auth.ServiceTokenClient;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GenerationJobClient(lbs-common、#1483でmedia-serviceから移設)の回帰テスト(issue #1083)。
 *
 * <p>#1083: ModelInstallJobRunnerが起動時のユーザーBearerトークンを長時間の非同期ジョブ全体で
 * 使い回していたため、Keycloakの{@code accessTokenLifespan}(既定300秒)を超えるチェックポイント
 * ダウンロード中にトークンが失効し、進捗・完了(done/failed)通知が握りつぶされてジョブが
 * DB上{@code running}のまま残っていた。本テストは以下を固定する。
 *
 * <ul>
 *   <li>{@code updateStatus}はもはや呼び出し元のBearerトークンを転送せず、
 *       {@link ServiceTokenClient}のClient Credentials Grantで取得した本サービス自身の
 *       アクセストークンを付与する(ユーザートークンの残り寿命に依存しなくなる)。</li>
 *   <li>終端状態(done/failed)の通知はai-serviceへの到達に失敗しても再試行し、
 *       再試行を使い切った場合はERRORとして記録する(黙って握りつぶさない)。</li>
 *   <li>進捗更新(running)は引き続きベストエフォートだが、認証エラー(401/403)の連続発生時に
 *       0.5秒間隔でWARNログが無制限に出続けることはない。</li>
 * </ul>
 *
 * <p>{@code SyncServiceClient}は独自の{@code requestFactory}を設定するため
 * {@code MockRestServiceServer}を差し込めず(ai-service向け)、実際のHTTPサーバーを使う
 * (services/log-writer の GenerationJobClientTest と同じ方式)。トークンエンドポイントも
 * 同じサーバー内の別パスとして待ち受ける。
 *
 * <p>AC1〜3(UI観測可能な結果)は本テストとATの役割分担で検証する。既存AT
 * ({@code comfyui-checkpoints.feature} の導入シナリオ)が導入→ポーリング→doneの因果を
 * 実機ComfyUIで既に検証しており、本Issueが変えたのはその配線(認証方式・終端通知の再試行)
 * だけなので、300秒超のトークン失効条件はAT側では再現せず本テストで固定する
 * (根拠は issue #1083 のコメント参照)。
 */
class GenerationJobClientTest {

    private HttpServer server;
    private GenerationJobClient client;
    private final List<String> patchAuthHeaders = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> lastPatchBody = new AtomicReference<>();
    private final AtomicInteger patchAttempts = new AtomicInteger(0);
    private final AtomicInteger tokenRequests = new AtomicInteger(0);

    /** PATCH呼び出しごとに、順番に返すステータスコード。尽きたら最後の値を使い続ける。 */
    private volatile int[] patchResponseStatuses = {200};

    /** POST(create)応答。テストごとに差し替える。 */
    private volatile int createResponseStatus = 201;
    private volatile String createResponseBody = "{\"id\":1,\"type\":\"comfyui_checkpoint_download\",\"status\":\"running\"}";

    private String baseUri;
    private ServiceTokenClient serviceTokenClient;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/realms/letsblog/protocol/openid-connect/token", this::respondToken);
        server.createContext("/api/internal/ai/generation-jobs/", this::respondPatch);
        server.createContext("/api/internal/ai/generation-jobs", this::respondCreate);
        server.start();

        baseUri = "http://127.0.0.1:" + server.getAddress().getPort();
        serviceTokenClient = new ServiceTokenClient(
                RestClient.builder(), baseUri + "/realms/letsblog/protocol/openid-connect/token",
                "letsblog-services", "test-secret");
        // "ai-service"のサーキットブレーカーはJVM全体で共有されるため、テストごとに独立した
        // レジストリを渡してテスト間の状態漏れを防ぐ(GenerationJobClientの package-private
        // テスト用コンストラクタ参照)。
        client = new GenerationJobClient(
                RestClient.builder(), baseUri, serviceTokenClient, CircuitBreakerRegistry.ofDefaults());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void respondToken(HttpExchange exchange) throws IOException {
        tokenRequests.incrementAndGet();
        byte[] bytes = "{\"access_token\":\"service-token-1\",\"expires_in\":3600}"
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void respondCreate(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = createResponseBody == null
                ? new byte[0]
                : createResponseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(createResponseStatus, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void respondPatch(HttpExchange exchange) throws IOException {
        int attempt = patchAttempts.getAndIncrement();
        List<String> auth = exchange.getRequestHeaders().get("Authorization");
        patchAuthHeaders.add(auth == null ? null : String.join(",", auth));
        lastPatchBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

        int[] statuses = patchResponseStatuses;
        int status = attempt < statuses.length ? statuses[attempt] : statuses[statuses.length - 1];
        byte[] bytes = status >= 400 ? "{\"error\":\"denied\"}".getBytes(StandardCharsets.UTF_8) : new byte[0];
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    @DisplayName("進捗更新は呼び出し元のBearerトークンではなく、サービス自身のClient Credentialsトークンを付与する")
    void 進捗更新は自身のトークンを使う() {
        client.updateStatus(1L, "running", "{}");

        assertThat(patchAuthHeaders).hasSize(1);
        assertThat(patchAuthHeaders.get(0)).isEqualTo("Bearer service-token-1");
    }

    @Test
    @DisplayName("終端状態(done)の通知は、下流が一時的に失敗しても再試行して最終的に成功する")
    void 終端通知は再試行して成功する() {
        patchResponseStatuses = new int[] {503, 503, 200};

        client.updateStatus(2L, "done", "{\"success\":\"true\"}");

        assertThat(patchAttempts.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("終端状態(failed)の通知が再試行を使い切っても例外を投げず、ERRORとして記録する")
    void 終端通知が再試行を使い切るとERRORログに記録する() {
        patchResponseStatuses = new int[] {503, 503, 503};

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(GenerationJobClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            client.updateStatus(3L, "failed", "{\"error\":\"x\"}");
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(patchAttempts.get()).isGreaterThanOrEqualTo(3);
        boolean errorLogged = appender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.ERROR
                        && event.getFormattedMessage().contains("3"));
        assertThat(errorLogged).as("終端通知の失敗はERRORとして記録されること").isTrue();
    }

    @Test
    @DisplayName("進捗更新中に認証エラー(401)が連続しても、0.5秒間隔で無制限にWARNログを出し続けない")
    void 認証エラー連続時のWARNログは無制限に出ない() {
        patchResponseStatuses = new int[] {401};

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(GenerationJobClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            // ダウンロード中の0.5秒間隔での進捗報告を模した連続呼び出し。
            for (int i = 0; i < 20; i++) {
                client.updateStatus(4L, "running", "{}");
            }
        } finally {
            logger.detachAppender(appender);
        }

        long warnCount = appender.list.stream().filter(event -> event.getLevel() == Level.WARN).count();
        assertThat(warnCount)
                .as("20回連続の401失敗に対してWARNは高々数回に抑制されること(無制限のログ洪水にならない)")
                .isLessThan(20L);
    }

    @Test
    @DisplayName("resultPayloadがnullなら空文字として送る")
    void resultPayloadがnullでも送信できる() {
        client.updateStatus(5L, "running", null);

        assertThat(lastPatchBody.get()).contains("\"resultPayload\":\"\"");
    }

    @Test
    @DisplayName("createは呼び出し元のBearerトークンを転送してジョブを作成する")
    void createはジョブを作成する() {
        GenerationJobSummary created = client.create("comfyui_checkpoint_download", "{}", "Bearer caller-token");

        assertThat(created.id()).isEqualTo(1L);
        assertThat(created.status()).isEqualTo("running");
    }

    @Test
    @DisplayName("createがai-serviceから空の応答を受け取ったら明確なエラーにする")
    void create空応答は例外になる() {
        createResponseBody = "null";

        assertThatThrownBy(() -> client.create("comfyui_checkpoint_download", "{}", "Bearer caller-token"))
                .isInstanceOf(GenerationJobBridgeException.class);
    }

    @Test
    @DisplayName("createが失敗したら明確なエラーとして伝播させる(ベストエフォートにしない)")
    void create失敗は例外になる() {
        createResponseStatus = 500;
        createResponseBody = "{\"error\":\"boom\"}";

        assertThatThrownBy(() -> client.create("comfyui_checkpoint_download", "{}", "Bearer caller-token"))
                .isInstanceOf(GenerationJobBridgeException.class)
                .hasMessageContaining("ai-serviceの/api/generation-jobs作成呼び出しに失敗しました");
    }

    @Test
    @DisplayName("requestPayloadがnullでもcreateできる")
    void createはrequestPayloadがnullでも呼べる() {
        GenerationJobSummary created = client.create("comfyui_checkpoint_delete", null, "Bearer caller-token");

        assertThat(created.id()).isEqualTo(1L);
    }

    @Test
    @DisplayName("公開コンストラクタ(既定のCircuitBreakerRegistryを使う版)でも進捗更新できる")
    void 公開コンストラクタでも動く() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("serviceTokenClient", serviceTokenClient);
        GenerationJobClient publicClient = new GenerationJobClient(
                RestClient.builder(), baseUri, beanFactory.getBeanProvider(ServiceTokenClient.class));

        publicClient.updateStatus(6L, "running", "{}");

        assertThat(patchAttempts.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("進捗更新が認証エラーではない4xx(400)で失敗しても、抑制せず毎回WARNを出す")
    void 認証エラーではない4xxのWARNは抑制しない() {
        patchResponseStatuses = new int[] {400};

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(GenerationJobClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            client.updateStatus(7L, "running", "{}");
            client.updateStatus(7L, "running", "{}");
        } finally {
            logger.detachAppender(appender);
        }

        long warnCount = appender.list.stream().filter(event -> event.getLevel() == Level.WARN).count();
        assertThat(warnCount).isEqualTo(2L);
    }

    @Test
    @DisplayName("進捗更新が403でも認証エラーとして抑制対象になる")
    void 進捗更新403も認証エラーとして扱う() {
        patchResponseStatuses = new int[] {403};

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(GenerationJobClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            client.updateStatus(8L, "running", "{}");
            client.updateStatus(8L, "running", "{}");
        } finally {
            logger.detachAppender(appender);
        }

        long warnCount = appender.list.stream().filter(event -> event.getLevel() == Level.WARN).count();
        assertThat(warnCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("接続自体ができない(通信断)場合は認証エラー扱いにせず、WARNを抑制しない")
    void 接続断は認証エラー扱いしない() throws IOException {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }
        String deadBaseUri = "http://127.0.0.1:" + unusedPort;
        GenerationJobClient deadClient = new GenerationJobClient(
                RestClient.builder(), deadBaseUri, serviceTokenClient, CircuitBreakerRegistry.ofDefaults());

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(GenerationJobClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            deadClient.updateStatus(9L, "running", "{}");
            deadClient.updateStatus(9L, "running", "{}");
        } finally {
            logger.detachAppender(appender);
        }

        long warnCount = appender.list.stream().filter(event -> event.getLevel() == Level.WARN).count();
        assertThat(warnCount).isEqualTo(2L);
    }
}
