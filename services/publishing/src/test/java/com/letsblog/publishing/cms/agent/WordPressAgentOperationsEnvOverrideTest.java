package com.letsblog.publishing.cms.agent;

import com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #1264 受け入れ基準2: application.ymlの環境変数プレースホルダ
 * ({@code ${WORDPRESS_AGENT_READ_TIMEOUT_SECONDS:...}})経由でリードタイムアウトを上書きできることを、
 * {@code @Value}解決を実際に通して検証する(WordPressBulkManagementClientEnvOverrideTestと同じ手法)。
 */
class WordPressAgentOperationsEnvOverrideTest {

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
    void 環境変数WORDPRESS_AGENT_READ_TIMEOUT_SECONDSを変えると打ち切り時刻が変わる() throws IOException {
        startNeverRespondingServer();

        long shortMs = measureElapsedMs("1");
        long longMs = measureElapsedMs("3");

        assertTrue(longMs > shortMs + 1_000, "short=" + shortMs + "ms long=" + longMs + "ms");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private long measureElapsedMs(String readTimeoutSeconds) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            Properties envOverride = new Properties();
            envOverride.setProperty("WORDPRESS_AGENT_READ_TIMEOUT_SECONDS", readTimeoutSeconds);
            context.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("envOverride", (Map) envOverride));

            Properties applicationYmlEquivalent = new Properties();
            applicationYmlEquivalent.setProperty("app.wordpress-provision-base-url",
                    "http://127.0.0.1:" + httpServer.getAddress().getPort());
            applicationYmlEquivalent.setProperty("app.wordpress-provision-token", "token");
            applicationYmlEquivalent.setProperty("app.wordpress-agent-connect-timeout-seconds",
                    "${WORDPRESS_AGENT_CONNECT_TIMEOUT_SECONDS:5}");
            applicationYmlEquivalent.setProperty("app.wordpress-agent-read-timeout-seconds",
                    "${WORDPRESS_AGENT_READ_TIMEOUT_SECONDS:60}");

            PropertySourcesPlaceholderConfigurer configurer = new PropertySourcesPlaceholderConfigurer();
            configurer.setEnvironment(context.getEnvironment());
            configurer.setProperties(applicationYmlEquivalent);
            context.addBeanFactoryPostProcessor(configurer);

            context.registerBean(RestClient.Builder.class, () -> RestClient.builder());
            context.registerBean(WordPressAgentOperations.class);
            context.refresh();

            WordPressAgentOperations operations = context.getBean(WordPressAgentOperations.class);
            WordPressCredentials creds = new WordPressCredentials("http://wordpress/sites/main", "admin",
                    "AGENT", null, null, null, null, null, null, "main");
            Instant startedAt = Instant.now();
            assertThrows(AgentOperationException.class, () -> operations.resolveCategories(creds, List.of("a")));
            return Duration.between(startedAt, Instant.now()).toMillis();
        }
    }

    private void startNeverRespondingServer() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newFixedThreadPool(4);
        httpServer.setExecutor(serverExecutor);
        httpServer.createContext("/", exchange -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
        });
        httpServer.start();
    }
}
