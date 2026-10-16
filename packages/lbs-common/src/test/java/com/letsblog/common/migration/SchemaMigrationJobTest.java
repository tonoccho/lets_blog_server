package com.letsblog.common.migration;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaMigrationJobTest {

    private DataSource targetDataSource;
    private SchemaMigrationJob job;

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        this.targetDataSource = ds;
        this.job = new SchemaMigrationJob(targetDataSource);

        try (Connection c = targetDataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE copied_items (id INT PRIMARY KEY, content VARCHAR(255))");
        }
    }

    private MigrationBatchStep copyFromSource(List<String> source) {
        return (targetConnection, batchSize) -> {
            int startId = currentMaxId(targetConnection) + 1;
            int copied = 0;
            try (PreparedStatement insert = targetConnection.prepareStatement(
                    "INSERT INTO copied_items (id, content) VALUES (?, ?)")) {
                for (int id = startId; id <= source.size() && copied < batchSize; id++) {
                    insert.setInt(1, id);
                    insert.setString(2, source.get(id - 1));
                    insert.executeUpdate();
                    copied++;
                }
            }
            return copied;
        };
    }

    private int currentMaxId(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT COALESCE(MAX(id), 0) AS max_id FROM copied_items")) {
            rs.next();
            return rs.getInt("max_id");
        }
    }

    private int countRows() throws SQLException {
        try (Connection c = targetDataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) AS cnt FROM copied_items")) {
            rs.next();
            return rs.getInt("cnt");
        }
    }

    @Test
    void 全件をバッチに分けてコピーし完了する() {
        List<String> source = List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j");

        MigrationResult result = job.run("copy-items", copyFromSource(source), 3);

        assertEquals(10, result.rowsCopied());
        assertFalse(result.alreadyCompleted());
    }

    @Test
    void 完了済みのジョブを再実行しても何もしない() throws SQLException {
        List<String> source = List.of("a", "b", "c");
        job.run("copy-items", copyFromSource(source), 2);

        AtomicInteger callCount = new AtomicInteger();
        MigrationBatchStep countingStep = (conn, batchSize) -> {
            callCount.incrementAndGet();
            return copyFromSource(source).copyBatch(conn, batchSize);
        };

        MigrationResult second = job.run("copy-items", countingStep, 2);

        assertTrue(second.alreadyCompleted());
        assertEquals(0, second.rowsCopied());
        assertEquals(0, callCount.get(), "完了済みならバッチ処理は一度も呼ばれない");
        assertEquals(3, countRows(), "重複コピーされていない");
    }

    @Test
    void 途中まで入っている行がある場合はその続きから再開する() throws SQLException {
        try (Connection c = targetDataSource.getConnection()) {
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO copied_items (id, content) VALUES (?, ?)")) {
                insert.setInt(1, 1);
                insert.setString(2, "a");
                insert.executeUpdate();
            }
        }
        List<String> source = List.of("a", "b", "c", "d");

        MigrationResult result = job.run("resume-items", copyFromSource(source), 10);

        assertEquals(3, result.rowsCopied(), "既に入っている1件を除いた残り3件だけコピーする");
        assertEquals(4, countRows());
    }

    @Test
    void 状態表に完了以外の行があれば実行する() throws SQLException {
        try (Connection c = targetDataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE _migration_state (job_name VARCHAR(255) NOT NULL PRIMARY KEY, "
                    + "status VARCHAR(20) NOT NULL, rows_copied BIGINT NOT NULL DEFAULT 0, updated_at TIMESTAMP NOT NULL)");
            s.execute("INSERT INTO _migration_state VALUES ('half-done', 'RUNNING', 1, CURRENT_TIMESTAMP)");
        }

        MigrationResult result = job.run("half-done", copyFromSource(List.of("a", "b")), 10);

        assertFalse(result.alreadyCompleted());
        assertEquals(2, result.rowsCopied());
    }

    @Test
    void 進捗ログの間隔を超える件数をコピーしても完了する() {
        AtomicInteger calls = new AtomicInteger();
        MigrationBatchStep bigBatches = (conn, batchSize) -> calls.incrementAndGet() <= 2 ? 5000 : 0;

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SchemaMigrationJob.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MigrationResult result;
        try {
            result = job.run("big", bigBatches, 5000);
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals(10_000, result.rowsCopied());
        assertEquals(3, calls.get());
        assertEquals(2, appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("件コピー済み")).count(), "5000件ごとに進捗ログ");
    }

    /** DBへの接続口(DataSource)だけを差し替える。getConnectionの結果を呼び出し側が決める。 */
    private static DataSource dataSourceWhoseConnection(java.util.concurrent.Callable<Connection> connection) {
        return (DataSource) java.lang.reflect.Proxy.newProxyInstance(
                SchemaMigrationJobTest.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if (!"getConnection".equals(method.getName())) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    return connection.call();
                });
    }

    @Test
    void 接続を取得できなければMigrationExceptionにする() {
        DataSource broken = dataSourceWhoseConnection(() -> {
            throw new SQLException("connection refused");
        });

        MigrationException e = org.junit.jupiter.api.Assertions.assertThrows(MigrationException.class,
                () -> new SchemaMigrationJob(broken).run("x", (c, n) -> 0));
        assertEquals("移行ジョブ[x]の実行に失敗しました", e.getMessage());
    }

    @Test
    void DataSourceがnullの接続を返すとNullPointerExceptionになる() {
        DataSource returnsNull = dataSourceWhoseConnection(() -> null);

        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> new SchemaMigrationJob(returnsNull).run("x", (c, n) -> 0));
    }
}
