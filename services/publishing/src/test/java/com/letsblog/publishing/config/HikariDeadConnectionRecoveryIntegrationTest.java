package com.letsblog.publishing.config;

import com.letsblog.publishing.config.support.DisposableMysqlContainer;
import com.letsblog.publishing.config.support.FreezableTcpProxy;
import com.letsblog.publishing.config.support.ProductionDataSourceYamlSettings;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.jdbc.health.DataSourceHealthIndicator;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * issue #1095: 「mysqlコンテナを再作成すると、その時点で確立済みだったHikariCPの接続が
 * サイレントに応答不能になり、サービスを再起動するまでプールが回復しない」障害を、
 * 実際に本番の{@code services/publishing/src/main/resources/application.yml}に記述された
 * DataSource/HikariCP設定を使って再現・検証する。
 *
 * <p><b>再現方法。</b>実際に{@code lbs-mysql}コンテナを作り直すと、現在稼働中の依存JVM
 * サービス群(このリポジトリの開発スタックそのもの)が本Issueの障害をそのまま踏んでしまう
 * ため、本テストは代わりに (1) {@code lbs-mysql}とは無関係の使い捨てMySQLコンテナ
 * ({@link DisposableMysqlContainer})と (2) 接続を「ソケットレベルで無応答にする」ことが
 * できるTCPリレー({@link FreezableTcpProxy})を使う。{@link FreezableTcpProxy#freezeExistingConnections()}
 * は、確立済みの接続に対してFIN/RSTを一切送らずバイト中継だけを止める——mysqlコンテナが
 * 再作成されたときに古いTCP接続が陥る状態(相手がいなくなり、応答が永久に来ない)を
 * 決定論的に模している。新規接続は使い捨てコンテナ(生きたまま)へ正常につながる点も、
 * 「mysql再作成後、新規接続は成功するが既存接続だけ死ぬ」という実際の非対称性と一致する。
 *
 * <p><b>本番実行が要求するdocker。</b>このテストは実際にDockerコンテナを起動する
 * (ADR-0006の「Testcontainersは使わない」は通常のドメインロジックテストの方針であり、
 * HikariCP自体の障害復旧を検証する本テストは対象外——本文冒頭のJavadoc参照)。
 * ローカル/CI双方でdockerが使えない環境では実行できないため、システムプロパティ
 * {@code lbs.dockerAvailable=true}が明示的に指定されたときだけ実行する。
 */
@EnabledIfSystemProperty(named = "lbs.dockerAvailable", matches = "true")
class HikariDeadConnectionRecoveryIntegrationTest {

    private DisposableMysqlContainer mysql;
    private FreezableTcpProxy proxy;
    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        mysql = DisposableMysqlContainer.startAndWaitUntilReady();
        proxy = new FreezableTcpProxy("127.0.0.1", mysql.getHostPort());

        ProductionDataSourceYamlSettings settings =
                ProductionDataSourceYamlSettings.loadFromMainApplicationYml();
        String query = settings.extractQueryStringFromUrlDefault();
        String jdbcUrl = "jdbc:mysql://127.0.0.1:" + proxy.getLocalPort() + "/"
                + DisposableMysqlContainer.DATABASE
                + (query.isEmpty() ? "" : "?" + query);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(DisposableMysqlContainer.USERNAME);
        config.setPassword(DisposableMysqlContainer.PASSWORD);
        // プール自体のサイズはこのテストの関心事ではない(検証したいのは「死んだ接続からの
        // 回復」であって枯渇待ち行列の挙動ではない)ため、テスト速度のために小さくする。
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(1);
        // application.ymlに実際に書かれた値をそのまま使う。これが本テストの核心——
        // 本番設定が変わらない限りテストの意味も変わらない。
        config.setMaxLifetime(settings.hikariLong("max-lifetime", 1_800_000L));
        config.setKeepaliveTime(settings.hikariLong("keepalive-time", 0L));
        config.setConnectionTimeout(settings.hikariLong("connection-timeout", 30_000L));
        config.setValidationTimeout(Math.min(
                settings.hikariLong("validation-timeout", 5_000L), 5_000L));
        dataSource = new HikariDataSource(config);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (dataSource != null) {
            dataSource.close();
        }
        if (proxy != null) {
            proxy.close();
        }
        if (mysql != null) {
            mysql.close();
        }
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void mysql再作成相当でハングした接続はsocketTimeoutで打ち切られ再起動なしで回復する() throws Exception {
        // 1. 接続を確立し、クエリを実行中(=プールから見て"active")の状態を作る。
        Connection active = dataSource.getConnection();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch queryStarted = new CountDownLatch(1);
        AtomicReference<SQLException> observedFailure = new AtomicReference<>();

        java.util.concurrent.Future<?> blockedQuery = executor.submit(() -> {
            try (Statement statement = active.createStatement()) {
                queryStarted.countDown();
                // mysqlコンテナ再作成後、対向がいなくなったソケットに対してクエリを送った
                // 状態を模す(SLEEPで「応答を待ち続ける」状態を作ってからfreezeする)。
                statement.executeQuery("SELECT SLEEP(120)");
            } catch (SQLException e) {
                observedFailure.set(e);
            }
        });

        assertTrue(queryStarted.await(10, TimeUnit.SECONDS), "クエリが開始されなかった");
        // クエリが飛んだ直後にソケットを無応答化する = mysqlコンテナの再作成そのもの。
        Thread.sleep(500);
        proxy.freezeExistingConnections();

        // 2. socketTimeout(URLパラメータ)が効いていれば、無期限にハングせず有限時間で
        //    例外になるはず。現状(修正前)のapplication.ymlにはsocketTimeoutが無いため、
        //    このブロッキング呼び出しは@Timeout(90秒)を超えてもリターンしない
        //    ==このテストがここでタイムアウト・失敗する(RED)。
        blockedQuery.get(75, TimeUnit.SECONDS);
        executor.shutdownNow();

        SQLException failure = observedFailure.get();
        if (failure == null) {
            fail("凍結した接続へのクエリが失敗せずに完了してしまった(想定外)");
        }

        // 3. 自己回復の検証: 死んだ接続がプールに残り続けず、新しい借用は
        //    (使い捨てコンテナ自体は生きているので)速やかに成功するはず。
        assertDoesNotThrow(() -> {
            try (Connection recovered = dataSource.getConnection();
                    Statement statement = recovered.createStatement()) {
                statement.execute("SELECT 1");
            }
        }, "mysql再作成相当のあとも、サービスを再起動せずに新しいクエリが成功するはず");

        active.close();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void プールの全接続が凍結され枯渇している間はヘルスチェックがUPを報告しない() throws Exception {
        // issue #1095 受け入れ基準の代替項目(自力回復が難しい場合はヘルスチェックでUP以外を
        // 報告する)の検証。本番のlbs-publishingが実際に踏んだ障害(active=10, idle=0,
        // waiting=3)を、プールサイズ2で縮小再現する——両方の接続を長時間クエリで
        // active化したままfreezeし、その状態でSpring Bootの標準
        // DataSourceHealthIndicator(actuator/healthのDB判定が内部で使うのと同じ実装)を
        // 直接呼び出す。プールに空きスロットが無いため、ヘルスチェック自身のgetConnection()が
        // connection-timeoutまでブロックしたのち失敗し、UP以外(DOWN)を返すはず。
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch bothStarted = new CountDownLatch(2);
        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try (Connection connection = dataSource.getConnection();
                        Statement statement = connection.createStatement()) {
                    bothStarted.countDown();
                    statement.executeQuery("SELECT SLEEP(120)");
                } catch (SQLException ignored) {
                    // freeze後にsocketTimeout等で打ち切られること自体は1つ目のテストの関心事。
                    // ここではヘルスチェックの応答だけを見る。
                }
            });
        }

        assertTrue(bothStarted.await(10, TimeUnit.SECONDS), "接続が確立されなかった");
        Thread.sleep(500);
        proxy.freezeExistingConnections();

        DataSourceHealthIndicator healthIndicator = new DataSourceHealthIndicator(dataSource);
        Health health = healthIndicator.health();

        assertEquals(Status.DOWN, health.getStatus(),
                "全接続が凍結されプールが枯渇している間は、ヘルスチェックはUP以外を報告するべき");

        executor.shutdownNow();
    }
}
