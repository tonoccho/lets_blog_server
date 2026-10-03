package com.letsblog.common.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GenerationJobClient}の回帰テスト(issue #825)。
 *
 * <p>#825では2つの不具合が重なっていた。(1) 本クライアントがai-serviceへ移設済みの
 * {@code GET /api/generation-jobs}をlegacy-apiに問い合わせ続けて404を受けていた。
 * (2) {@code UnifiedOperationLogService}がその失敗を捕捉せず統合ログAPI全体が502になっていた。
 *
 * <p>(2)の修正で失敗はHTTPレスポンスに現れなくなった。つまり<b>今後この経路が壊れても
 * 画面は200のまま「AIジョブだけが出ない」状態になる</b>。#825は502という大きな症状があったから
 * 発見されたが、次は静かに壊れる。そこで壊れ方を機械的に検出できるよう、
 * 問い合わせ先・レスポンス形状・認証ヘッダーの転送をここで固定する。
 *
 * <p>{@code SyncServiceClient}は渡された{@code RestClient.Builder}を{@code clone()}して
 * 独自の{@code requestFactory}を設定するため{@code MockRestServiceServer}では差し込めない。
 * その場限りのHTTPサーバーを立てて実通信させる
 * ({@code services/legacy-api/.../PlatformServiceClientTest}と同じ方式)。
 */
@DisplayName("GenerationJobClient: ai-serviceへの問い合わせ(issue #825)")
class GenerationJobClientListTest {

    /** ai-serviceの{@code GenerationJobResponse}が実際に返す5フィールド({@code updatedAt}を含む)。 */
    private static final String AI_SERVICE_JSON = """
            [
              {"id":2,"type":"IMAGE","status":"COMPLETED",
               "createdAt":"2026-08-31T10:00:00","updatedAt":"2026-08-31T10:05:00"},
              {"id":1,"type":"ARTICLE","status":"FAILED",
               "createdAt":"2026-08-31T09:00:00","updatedAt":"2026-08-31T09:01:00"}
            ]
            """;

    private HttpServer server;
    private GenerationJobClient client;
    private final AtomicReference<String> requestedPath = new AtomicReference<>();
    private final AtomicReference<String> requestedAuth = new AtomicReference<>();
    private volatile int responseStatus = 200;
    private volatile String responseBody = AI_SERVICE_JSON;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        // listRecentはservice token不要(log-writerはServiceTokenClientを持たない)ためnullを渡す。
        client = new GenerationJobClient(
                RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort(), null,
                io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.ofDefaults());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void respond(HttpExchange exchange) throws IOException {
        requestedPath.set(exchange.getRequestURI().getPath());
        List<String> auth = exchange.getRequestHeaders().get("Authorization");
        requestedAuth.set(auth == null ? null : String.join(",", auth));

        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /**
     * ai-serviceが実際に返すJSON({@code updatedAt}を含む5フィールド)が
     * {@link GenerationJobSummary}の4フィールドへデシリアライズできること。
     *
     * <p>ai-service側のDTOでフィールド名や型が変わると、#825の縮退により
     * デシリアライズ失敗もAI_JOBソースの除外として無音で処理される。ここで固定しておく。
     */
    @Test
    @DisplayName("ai-serviceのレスポンス形状をデシリアライズできる(余剰のupdatedAtは無視)")
    void レスポンス形状() {
        List<GenerationJobSummary> jobs = client.listRecent("Bearer token");

        assertThat(jobs).hasSize(2);
        assertThat(jobs.get(0).id()).isEqualTo(2L);
        assertThat(jobs.get(0).type()).isEqualTo("IMAGE");
        assertThat(jobs.get(0).status()).isEqualTo("COMPLETED");
        assertThat(jobs.get(0).createdAt()).isNotNull();
        assertThat(jobs.get(1).id()).isEqualTo(1L);
    }

    /**
     * ai-serviceは#1535以降、日時をZ終端のRFC 3339で返す。{@link GenerationJobSummary}は
     * {@code LocalDateTime}のままなので、Z付きを読めなくなると#825の縮退でAI_JOBが無音で消える。
     */
    @Test
    @DisplayName("Z終端のRFC 3339日時(#1535)をデシリアライズできる")
    void Z終端の日時() {
        responseBody = """
                [{"id":1,"type":"IMAGE","status":"COMPLETED",
                  "createdAt":"2026-09-08T20:03:35Z","updatedAt":"2026-09-08T20:05:00Z"}]
                """;

        List<GenerationJobSummary> jobs = client.listRecent("Bearer token");

        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).createdAt()).isEqualTo(java.time.LocalDateTime.of(2026, 9, 8, 20, 3, 35));
    }

    @Test
    @DisplayName("本文が空(JSON null)のレスポンスは空リストとして扱う")
    void 空本文は空リスト() {
        responseBody = "null";

        assertThat(client.listRecent("Bearer token")).isEmpty();
    }

    @Test
    @DisplayName("/api/generation-jobs を叩く")
    void 問い合わせパス() {
        client.listRecent("Bearer token");

        assertThat(requestedPath.get()).isEqualTo("/api/generation-jobs");
    }

    /**
     * ai-serviceのSecurityConfigは{@code /api/generation-jobs}に認証を要求する
     * (PUBLIC_PATHSはactuatorとAPIドキュメントのみ)。呼び出し元のトークンを転送しないと401になる。
     */
    @Test
    @DisplayName("呼び出し元のAuthorizationヘッダーを転送する")
    void 認証転送() {
        client.listRecent("Bearer caller-token");

        assertThat(requestedAuth.get()).isEqualTo("Bearer caller-token");
    }

    @Test
    @DisplayName("bearerTokenがnullならAuthorizationヘッダーを付けない")
    void 認証なし() {
        client.listRecent(null);

        assertThat(requestedAuth.get()).isNull();
    }

    /**
     * #825の再発(向き先ミスによる404)が起きたとき、ログから切り分けられること。
     * 縮退により失敗はHTTPレスポンスに出ないため、例外メッセージが唯一の手がかりになる。
     */
    @Test
    @DisplayName("失敗時の例外メッセージに原因が含まれる")
    void 失敗時のメッセージ() {
        responseStatus = 404;
        responseBody = "{\"error\":\"Not Found\"}";

        assertThatThrownBy(() -> client.listRecent("Bearer token"))
                .isInstanceOf(GenerationJobBridgeException.class)
                .hasMessageContaining("ai-serviceの/api/generation-jobs呼び出しに失敗しました")
                // SyncServiceExceptionが組み立てる "[ai-service] GET /api/generation-jobs: ..." が
                // 連結されていること。ここが落ちると原因不明のWARNが1行出るだけになる。
                .hasMessageContaining("ai-service")
                .hasMessageContaining("404");
    }

    /**
     * 問い合わせ先の設定キーを固定する(issue #825)。
     *
     * <p>#825の原因は、このクライアントが{@code app.legacy-api-uri}を向いたまま
     * 取り残されていたこと。実行時のHTTPテストではベースURLを引数で渡すため、
     * 設定キーの取り違えは検出できない。{@code @Value}を直接読んで固定する
     * ({@code services/gateway}の{@code DownstreamHealthConfigContractTest}と同じ手法)。
     */
    @Test
    @DisplayName("ベースURLをapp.ai-service-uriから解決している")
    void 設定キー() {
        String expression = null;
        for (Constructor<?> constructor : GenerationJobClient.class.getDeclaredConstructors()) {
            if (constructor.getAnnotation(Autowired.class) == null) {
                continue;
            }
            for (Parameter parameter : constructor.getParameters()) {
                Value value = parameter.getAnnotation(Value.class);
                if (value != null) {
                    expression = value.value();
                }
            }
        }

        assertThat(expression)
                .as("問い合わせ先はai-service。legacy-apiには/api/generation-jobsが存在しない(#825)")
                .isEqualTo("${app.ai-service-uri}");
    }
}
