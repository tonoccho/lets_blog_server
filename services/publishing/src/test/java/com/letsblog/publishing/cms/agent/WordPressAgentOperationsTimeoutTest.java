package com.letsblog.publishing.cms.agent;

import com.letsblog.publishing.cms.AuthorProvisioningRequest;
import com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials;
import com.letsblog.publishing.cms.ConnectionCheckResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

/**
 * issue #1264: WordPressAgentOperationsのRestClientへ接続/リードタイムアウトを設定し、
 * タイムアウトとエラー応答とで文言を区別する。応答を返さないスタブ(実HTTPサーバ)相手に、
 * 設定したタイムアウト内で例外または失敗結果で戻ることを検証する。
 */
class WordPressAgentOperationsTimeoutTest {

    private static final Duration TIMEOUT = Duration.ofMillis(500);
    private static final long NEVER_RESPONDS_SLEEP_MS = 5_000;
    private static final long WITHIN_MS = 3_000;
    private static final String TIMEOUT_MESSAGE = "エージェントへの接続がタイムアウトしました";

    private HttpServer httpServer;
    private ExecutorService serverExecutor;
    private WordPressAgentOperations operations;

    @BeforeEach
    void setUp() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newFixedThreadPool(8);
        httpServer.setExecutor(serverExecutor);
        httpServer.createContext("/", exchange -> {
            try {
                Thread.sleep(NEVER_RESPONDS_SLEEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
        });
        httpServer.start();
        operations = new WordPressAgentOperations(RestClient.builder(),
                "http://127.0.0.1:" + httpServer.getAddress().getPort(), "token", TIMEOUT, TIMEOUT);
    }

    @AfterEach
    void tearDown() {
        httpServer.stop(0);
        serverExecutor.shutdownNow();
    }

    private WordPressCredentials creds() {
        return new WordPressCredentials("http://wordpress/sites/main", "admin",
                "AGENT", null, null, null, null, null, null, "main");
    }

    @Test
    void testConnection_応答が無くてもタイムアウト内に失敗結果で戻る() {
        Instant start = Instant.now();
        ConnectionCheckResult result = operations.testConnection(creds());
        long ms = Duration.between(start, Instant.now()).toMillis();

        assertFalse(result.ok());
        assertTrue(ms < WITHIN_MS, "elapsed=" + ms);
        assertTrue(result.failureReason().startsWith(TIMEOUT_MESSAGE), result.failureReason());
    }

    @Test
    void resolveCategories_応答が無くてもタイムアウト内にタイムアウト例外で戻る() {
        Instant start = Instant.now();
        AgentOperationException e = assertThrows(AgentOperationException.class,
                () -> operations.resolveCategories(creds(), List.of("a")));
        assertTrue(Duration.between(start, Instant.now()).toMillis() < WITHIN_MS);
        assertTrue(e.getMessage().startsWith(TIMEOUT_MESSAGE), e.getMessage());
    }

    @Test
    void resolveTags_応答が無くてもタイムアウト内にタイムアウト例外で戻る() {
        Instant start = Instant.now();
        AgentOperationException e = assertThrows(AgentOperationException.class,
                () -> operations.resolveTags(creds(), List.of("a")));
        assertTrue(Duration.between(start, Instant.now()).toMillis() < WITHIN_MS);
        assertTrue(e.getMessage().startsWith(TIMEOUT_MESSAGE), e.getMessage());
    }

    @Test
    void provisionAuthor_応答が無くてもタイムアウト内にタイムアウト例外で戻る() {
        Instant start = Instant.now();
        AgentOperationException e = assertThrows(AgentOperationException.class,
                () -> operations.provisionAuthor(creds(),
                        AuthorProvisioningRequest.of("a@example.com")));
        assertTrue(Duration.between(start, Instant.now()).toMillis() < WITHIN_MS);
        assertTrue(e.getMessage().startsWith(TIMEOUT_MESSAGE), e.getMessage());
    }

    @Test
    void postExists_応答が無くてもタイムアウト内に安全側のtrueで戻る() {
        Instant start = Instant.now();
        boolean exists = operations.postExists(creds(), "1");
        assertTrue(exists);
        assertTrue(Duration.between(start, Instant.now()).toMillis() < WITHIN_MS);
    }

    @Test
    void findAuthorIdByEmail_応答が無くてもタイムアウト内に空で戻る() {
        Instant start = Instant.now();
        assertTrue(operations.findAuthorIdByEmail(creds(), "a@example.com").isEmpty());
        assertTrue(Duration.between(start, Instant.now()).toMillis() < WITHIN_MS);
    }

    @Test
    void ソケットタイムアウトも接続失敗ではなくタイムアウトとして区別する() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WordPressAgentOperations mocked = new WordPressAgentOperations(builder, "http://wordpress:9000", "t");
        server.expect(requestTo("http://wordpress:9000/wp-cli/resolve-terms"))
                .andRespond(request -> { throw new SocketTimeoutException("Read timed out"); });

        AgentOperationException e = assertThrows(AgentOperationException.class,
                () -> mocked.resolveCategories(creds(), List.of("a")));

        assertTrue(e.getMessage().startsWith(TIMEOUT_MESSAGE), e.getMessage());
    }

    @Test
    void エラー応答はタイムアウトとは別の文言になる() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WordPressAgentOperations mocked = new WordPressAgentOperations(builder, "http://wordpress:9000", "t");
        server.expect(requestTo("http://wordpress:9000/wp-cli/resolve-terms"))
                .andRespond(withServerError().contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"boom\"}"));

        AgentOperationException e = assertThrows(AgentOperationException.class,
                () -> mocked.resolveCategories(creds(), List.of("a")));

        assertTrue(e.getMessage().startsWith("カテゴリ/タグの解決に失敗しました"), e.getMessage());
        assertFalse(e.getMessage().contains(TIMEOUT_MESSAGE));
    }

    @Test
    void 接続拒否はタイムアウトではなく接続失敗の文言になる() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WordPressAgentOperations mocked = new WordPressAgentOperations(builder, "http://wordpress:9000", "t");
        server.expect(requestTo("http://wordpress:9000/wp-cli/resolve-terms"))
                .andRespond(request -> { throw new IOException("connection refused"); });

        AgentOperationException e = assertThrows(AgentOperationException.class,
                () -> mocked.resolveCategories(creds(), List.of("a")));

        assertEquals("エージェントへの接続に失敗しました: I/O error on POST request for \"http://wordpress:9000/wp-cli/resolve-terms\": connection refused",
                e.getMessage());
    }
}
