package com.letsblog.publishing.provisioning;

import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #1123 受け入れ基準2: 応答を返さないprovision-agent(wordpress:9000固着、issue #1122)
 * 相手に、HikariCPが管理するJDBCコネクションを握ったまま
 * (TermComparisonServiceの{@code @Transactional(readOnly = true)}を模す)
 * WordPressBulkManagementClientを20並行で呼んでも、リードタイムアウトが設定されていれば
 * コネクションが有限時間で解放され、HikariCPの{@code active}が張り付かないことを検証する。
 *
 * <p>実DBの代わりにH2インメモリDBを使う。本テストの関心はMySQL固有の挙動ではなくHikariCP
 * 自体のプール枯渇なので、docker実MySQLを要する
 * {@link com.letsblog.publishing.config.HikariDeadConnectionRecoveryIntegrationTest}
 * (issue #1095)と異なり、docker無しで常時実行できる。
 */
class WordPressBulkManagementClientHikariPoolTest {

    private static final int POOL_SIZE = 10;
    private static final int CONCURRENT_CALLS = 20;
    private static final Duration CLIENT_TIMEOUT = Duration.ofMillis(500);

    private HttpServer httpServer;
    private ExecutorService serverExecutor;
    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newFixedThreadPool(CONCURRENT_CALLS + 4);
        httpServer.setExecutor(serverExecutor);
        httpServer.createContext("/", exchange -> {
            try {
                // クライアント側のタイムアウトより十分長く応答を返さない
                // (provision-agentの固着=issue #1122を模す)。
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        httpServer.start();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:wpbulk" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(POOL_SIZE);
        config.setConnectionTimeout(5_000);
        dataSource = new HikariDataSource(config);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
        if (httpServer != null) {
            httpServer.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void 応答の無いprovision_agentへ20並行で呼んでもHikariCPのコネクションプールが枯渇しない() throws Exception {
        String baseUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort();
        WordPressBulkManagementClient client =
                new WordPressBulkManagementClient(baseUrl, "token", CLIENT_TIMEOUT, CLIENT_TIMEOUT);

        ExecutorService callers = Executors.newFixedThreadPool(CONCURRENT_CALLS);
        CountDownLatch allDone = new CountDownLatch(CONCURRENT_CALLS);
        AtomicInteger connectionFailures = new AtomicInteger();

        for (int i = 0; i < CONCURRENT_CALLS; i++) {
            callers.submit(() -> {
                // @Transactional(readOnly = true)なTermComparisonServiceが、呼び出しの間
                // JDBCコネクションを握ったままである状況を再現する。
                try (Connection connection = dataSource.getConnection()) {
                    client.listCategories("site-a");
                } catch (SQLException e) {
                    connectionFailures.incrementAndGet();
                } finally {
                    allDone.countDown();
                }
            });
        }

        boolean completed = allDone.await(20, TimeUnit.SECONDS);
        callers.shutdownNow();

        assertTrue(completed,
                "20並行呼び出しが有限時間で完了しなかった(=いずれかのスレッドが無期限に待っている)");
        assertEquals(0, connectionFailures.get(), "コネクション取得自体が失敗した数");
        assertEquals(0, dataSource.getHikariPoolMXBean().getActiveConnections(),
                "全呼び出し完了後はコネクションが解放され、activeが張り付いていないはず");

        assertDoesNotThrow(() -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.createStatement().execute("SELECT 1");
            }
        }, "その後の通常のDBアクセスは成功するはず");
    }
}
