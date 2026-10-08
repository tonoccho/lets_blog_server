package com.letsblog.publishing.provisioning;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #1123 要件2/受け入れ基準3: タイムアウト値が、application.ymlの環境変数プレースホルダ
 * ({@code ${WORDPRESS_PROVISION_LISTING_TIMEOUT_SECONDS:...}})経由で上書きできることを、
 * Springの{@code @Value}解決を実際に通して検証する。コンストラクタへ直接Durationを渡す
 * {@link WordPressBulkManagementClientTest}と異なり、本テストは
 * 「環境変数名を変えると実際の打ち切り時刻が変わる」ことをapplication.ymlと同じ
 * プレースホルダ経由の解決で確認する(PlaywrightLazyBrowserTestと同じAnnotationConfig
 * ApplicationContextの手法)。
 */
class WordPressBulkManagementClientEnvOverrideTest {

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
    void 環境変数WORDPRESS_PROVISION_LISTING_TIMEOUT_SECONDSを変えると打ち切り時刻が変わる() throws IOException {
        startNeverRespondingServer();

        long shortElapsedMs = measureListCategoriesElapsedMs("1");
        long longElapsedMs = measureListCategoriesElapsedMs("3");

        assertTrue(longElapsedMs > shortElapsedMs + 1_000,
                "環境変数で指定した秒数を伸ばすと打ち切りまでの時間も伸びるはず。"
                        + "short=" + shortElapsedMs + "ms long=" + longElapsedMs + "ms");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private long measureListCategoriesElapsedMs(String listingTimeoutSeconds) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            Properties envOverride = new Properties();
            envOverride.setProperty("WORDPRESS_PROVISION_LISTING_TIMEOUT_SECONDS", listingTimeoutSeconds);
            context.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("envOverride", (Map) envOverride));

            // application.ymlの app.wordpress-provision-*-timeout-seconds と同じ、
            // 環境変数プレースホルダを持つ設定を再現する。
            Properties applicationYmlEquivalent = new Properties();
            applicationYmlEquivalent.setProperty("app.wordpress-provision-base-url", baseUrl());
            applicationYmlEquivalent.setProperty("app.wordpress-provision-token", "token");
            applicationYmlEquivalent.setProperty("app.wordpress-provision-listing-timeout-seconds",
                    "${WORDPRESS_PROVISION_LISTING_TIMEOUT_SECONDS:5}");
            applicationYmlEquivalent.setProperty("app.wordpress-provision-apply-timeout-seconds",
                    "${WORDPRESS_PROVISION_APPLY_TIMEOUT_SECONDS:60}");

            PropertySourcesPlaceholderConfigurer configurer = new PropertySourcesPlaceholderConfigurer();
            configurer.setEnvironment(context.getEnvironment());
            configurer.setProperties(applicationYmlEquivalent);
            context.addBeanFactoryPostProcessor(configurer);

            context.registerBean(WordPressBulkManagementClient.class);
            context.refresh();

            WordPressBulkManagementClient client = context.getBean(WordPressBulkManagementClient.class);
            Instant startedAt = Instant.now();
            try {
                client.listCategories("site-a");
            } catch (org.springframework.web.client.ResourceAccessException expectedTimeout) {
                // 打ち切り時刻の計測が目的。タイムアウトは失敗として伝わる(#1682)
            }
            return Duration.between(startedAt, Instant.now()).toMillis();
        }
    }

    private void startNeverRespondingServer() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newFixedThreadPool(4);
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
