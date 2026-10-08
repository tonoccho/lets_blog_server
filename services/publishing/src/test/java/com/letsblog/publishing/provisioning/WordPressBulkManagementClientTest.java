package com.letsblog.publishing.provisioning;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WordPressBulkManagementClientのRestClientタイムアウトの単体テスト(issue #1123)。
 * 常駐wordpressコンテナ内のprovision-agent(wordpress:9000)が固着して応答を返さない状況を、
 * JDK標準の{@link HttpServer}で「リクエストを受け取ってから設定タイムアウトより十分長く
 * スリープするだけで応答を返さない」ハンドラとして再現する
 * (LlmClientTest/ContentServiceClientTestと同じ手法)。無期限に待たず、設定した時間内に
 * 処理が戻ること(受け入れ基準1)、タイムアウト値を変えると打ち切り時刻が変わること
 * (受け入れ基準3)、タイムアウトとエラー応答とでログの文言が区別できること(要件3)を検証する。
 */
class WordPressBulkManagementClientTest {

    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(500);
    /** ハンドラのスリープ時間。設定タイムアウトより十分長くし、確実にタイムアウトで打ち切らせる。 */
    private static final long NEVER_RESPONDS_SLEEP_MS = 5_000;

    private HttpServer httpServer;
    private ExecutorService serverExecutor;

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void listCategories_応答が無いと設定したリードタイムアウト以内に失敗として伝える() throws IOException {
        assertTimesOutQuickly(client -> client.listCategories("site-a"));
    }

    @Test
    void listTags_応答が無いと設定したリードタイムアウト以内に失敗として伝える() throws IOException {
        assertTimesOutQuickly(client -> client.listTags("site-a"));
    }

    @Test
    void listPlugins_応答が無いと設定したリードタイムアウト以内に失敗として伝える() throws IOException {
        assertTimesOutQuickly(client -> client.listPlugins("site-a"));
    }

    @Test
    void listThemes_応答が無いと設定したリードタイムアウト以内に失敗として伝える() throws IOException {
        assertTimesOutQuickly(client -> client.listThemes("site-a"));
    }

    @Test
    void 一覧取得_エラー応答5xxは失敗として伝える() throws IOException {
        startFixedStatusServer(500, "internal error", "text/plain");
        WordPressBulkManagementClient client = newClient();

        assertThrows(RestClientResponseException.class, () -> client.listCategories("site-a"));
        assertThrows(RestClientResponseException.class, () -> client.listTags("site-a"));
        assertThrows(RestClientResponseException.class, () -> client.listPlugins("site-a"));
        assertThrows(RestClientResponseException.class, () -> client.listThemes("site-a"));
    }

    @Test
    void 一覧取得_エラー応答4xxは失敗として伝える() throws IOException {
        startFixedStatusServer(401, "unauthorized", "text/plain");
        WordPressBulkManagementClient client = newClient();

        assertThrows(RestClientResponseException.class, () -> client.listPlugins("site-a"));
        assertThrows(RestClientResponseException.class, () -> client.listCategories("site-a"));
    }

    @Test
    void 一覧取得_解釈できない応答はその他のRestClientExceptionとして失敗を伝える() throws IOException {
        startFixedStatusServer(200, "not json", "text/plain");
        WordPressBulkManagementClient client = newClient();

        assertThrows(RestClientException.class, () -> client.listPlugins("site-a"));
        assertThrows(RestClientException.class, () -> client.listTags("site-a"));
    }

    @Test
    void 一覧取得_正常な0件の応答は失敗にならず空リストを返す() throws IOException {
        startFixedStatusServer(200, "{\"plugins\":[],\"themes\":[],\"categories\":[],\"tags\":[]}",
                "application/json");
        WordPressBulkManagementClient client = newClient();

        assertTrue(client.listPlugins("site-a").isEmpty());
        assertTrue(client.listThemes("site-a").isEmpty());
        assertTrue(client.listCategories("site-a").isEmpty());
        assertTrue(client.listTags("site-a").isEmpty());
    }

    @Test
    void 一覧取得失敗のログは失敗の種類を区別できる() throws IOException {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(WordPressBulkManagementClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            startFixedStatusServer(500, "internal error", "text/plain");
            assertThrows(RestClientResponseException.class, () -> newClient().listPlugins("site-a"));
        } finally {
            logger.detachAppender(appender);
        }
        assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                && event.getFormattedMessage().contains("エラー応答")), "実際: " + appender.list);
    }

    private WordPressBulkManagementClient newClient() {
        return new WordPressBulkManagementClient(baseUrl(), "token", SHORT_TIMEOUT, SHORT_TIMEOUT);
    }

    private void assertTimesOutQuickly(java.util.function.Consumer<WordPressBulkManagementClient> call)
            throws IOException {
        startNeverRespondingServer();
        WordPressBulkManagementClient client = newClient();

        Instant startedAt = Instant.now();
        assertThrows(ResourceAccessException.class, () -> call.accept(client));
        Duration elapsed = Duration.between(startedAt, Instant.now());

        assertTrue(elapsed.toMillis() < NEVER_RESPONDS_SLEEP_MS,
                "設定したリードタイムアウト(" + SHORT_TIMEOUT + ")以内に戻るはず。実際: " + elapsed);
    }

    private void startFixedStatusServer(int status, String body, String contentType) throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        httpServer.start();
    }

    @Test
    void apply_応答が無いと設定したリードタイムアウト以内にFAILEDで戻る() throws IOException {
        startNeverRespondingServer();
        WordPressBulkManagementClient client =
                new WordPressBulkManagementClient(baseUrl(), "token", SHORT_TIMEOUT, SHORT_TIMEOUT);

        Instant startedAt = Instant.now();
        WordPressBulkManagementClient.BulkApplyResult result = client.apply(
                new WordPressBulkManagementClient.BulkApplyCommand(
                        "site-a", "activate-plugin", "some-plugin", null, null, null, null));
        Duration elapsed = Duration.between(startedAt, Instant.now());

        assertEquals("FAILED", result.status());
        assertTrue(elapsed.toMillis() < NEVER_RESPONDS_SLEEP_MS,
                "設定したリードタイムアウト(" + SHORT_TIMEOUT + ")以内に戻るはず。実際: " + elapsed);
    }

    @Test
    void タイムアウト値を変えると打ち切り時刻が変わる() throws IOException {
        startNeverRespondingServer();
        Duration veryShort = Duration.ofMillis(300);
        Duration longer = Duration.ofMillis(1_500);

        WordPressBulkManagementClient shortClient =
                new WordPressBulkManagementClient(baseUrl(), "token", veryShort, veryShort);
        Instant shortStart = Instant.now();
        assertThrows(ResourceAccessException.class, () -> shortClient.listCategories("site-a"));
        Duration shortElapsed = Duration.between(shortStart, Instant.now());

        WordPressBulkManagementClient longClient =
                new WordPressBulkManagementClient(baseUrl(), "token", longer, longer);
        Instant longStart = Instant.now();
        assertThrows(ResourceAccessException.class, () -> longClient.listCategories("site-a"));
        Duration longElapsed = Duration.between(longStart, Instant.now());

        assertTrue(longElapsed.toMillis() > shortElapsed.toMillis() + 500,
                "タイムアウト値を伸ばすと打ち切りまでの時間も伸びるはず。short=" + shortElapsed + " long=" + longElapsed);
    }

    @Test
    void apply_タイムアウトとエラー応答で異なるログメッセージになる() throws IOException {
        // 1. タイムアウトのケース。
        startNeverRespondingServer();
        WordPressBulkManagementClient timeoutClient =
                new WordPressBulkManagementClient(baseUrl(), "token", SHORT_TIMEOUT, SHORT_TIMEOUT);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(WordPressBulkManagementClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            timeoutClient.apply(new WordPressBulkManagementClient.BulkApplyCommand(
                    "site-a", "activate-plugin", "some-plugin", null, null, null, null));
        } finally {
            logger.detachAppender(appender);
        }
        httpServer.stop(0);
        serverExecutor.shutdownNow();

        boolean timeoutLogged = appender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("タイムアウト"));
        assertTrue(timeoutLogged, "応答が無い場合はタイムアウトと分かるログを出すこと。実際: " + appender.list);

        // 2. エラー応答のケース(即座に500を返す)。
        appender.list.clear();
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/", exchange -> {
            byte[] body = "internal error".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        httpServer.start();
        WordPressBulkManagementClient errorClient =
                new WordPressBulkManagementClient(baseUrl(), "token", SHORT_TIMEOUT, SHORT_TIMEOUT);

        logger.addAppender(appender);
        try {
            errorClient.apply(new WordPressBulkManagementClient.BulkApplyCommand(
                    "site-a", "activate-plugin", "some-plugin", null, null, null, null));
        } finally {
            logger.detachAppender(appender);
        }

        boolean errorResponseLogged = appender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("エラー応答")
                        && !event.getFormattedMessage().contains("タイムアウト"));
        assertTrue(errorResponseLogged,
                "エラー応答の場合はタイムアウトと異なる文言のログを出すこと。実際: " + appender.list);
    }

    private void startNeverRespondingServer() throws IOException {
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
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + httpServer.getAddress().getPort();
    }
}
