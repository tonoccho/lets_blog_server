package com.letsblog.common.db;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DataSourceを包んでJDBC実行を記録する部品の単体テスト(issue #1736)。実際のH2で動かす。 */
class DbQueryMetricsDataSourcePostProcessorTest {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(DbQueryRecorder.class);
    private JdbcDataSource raw;
    private DataSource wrapped;

    @BeforeEach
    void setUp() throws Exception {
        appender.start();
        logger.addAppender(appender);
        raw = new JdbcDataSource();
        raw.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        wrapped = (DataSource) new DbQueryMetricsDataSourcePostProcessor()
                .postProcessAfterInitialization(raw, "dataSource");
        try (Connection c = raw.getConnection(); Statement s = c.createStatement()) {
            s.execute("create table t (id int primary key, name varchar(50))");
        }
        DbQueryRecorder.end();
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        DbQueryRecorder.end();
    }

    @Test
    void DataSource以外のBeanはそのまま返す() {
        Object bean = new Object();

        assertSame(bean, new DbQueryMetricsDataSourcePostProcessor().postProcessAfterInitialization(bean, "x"));
    }

    @Test
    void DataSourceは包まれ元のDataSourceへunwrapできる() throws Exception {
        assertNotSame(raw, wrapped);
        assertTrue(wrapped.isWrapperFor(JdbcDataSource.class));
        assertSame(raw, wrapped.unwrap(JdbcDataSource.class));
    }

    @Test
    void PreparedStatementの実行ごとに1回と数える() throws Exception {
        DbQueryRecorder.begin(60_000, 10);
        try (Connection c = wrapped.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("insert into t (id, name) values (?, ?)")) {
                ps.setInt(1, 1);
                ps.setString(2, "a");
                assertEquals(1, ps.executeUpdate());
            }
            try (PreparedStatement ps = c.prepareStatement("select name from t where id = ?")) {
                ps.setInt(1, 1);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("a", rs.getString(1));
                }
                assertTrue(ps.execute());
            }
            try (PreparedStatement ps = c.prepareStatement("update t set name = ? where id = ?")) {
                ps.setString(1, "b");
                ps.setInt(2, 1);
                assertEquals(1L, ps.executeLargeUpdate());
            }
        }

        assertEquals(4, DbQueryRecorder.end().queries());
    }

    @Test
    void バッチは1回の実行として数える() throws Exception {
        DbQueryRecorder.begin(60_000, 10);
        try (Connection c = wrapped.getConnection();
             PreparedStatement ps = c.prepareStatement("insert into t (id, name) values (?, ?)")) {
            for (int i = 0; i < 3; i++) {
                ps.setInt(1, i);
                ps.setString(2, "n" + i);
                ps.addBatch();
            }
            assertEquals(3, ps.executeBatch().length);
            ps.setInt(1, 10);
            ps.setString(2, "n10");
            ps.addBatch();
            assertEquals(1, ps.executeLargeBatch().length);
        }

        assertEquals(2, DbQueryRecorder.end().queries());
    }

    @Test
    void 素のStatementは文字列リテラルと数値を伏せたテンプレートで記録する() throws Exception {
        DbQueryRecorder.begin(0, 10);
        try (Connection c = wrapped.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("insert into t (id, name) values (42, 'top-secret')");
            try (ResultSet rs = s.executeQuery("select * from t where name = 'it''s secret' and id = 42")) {
                assertFalse(rs.next());
            }
        }
        DbQueryRecorder.end();

        assertEquals(2, appender.list.size());
        String first = appender.list.get(0).getFormattedMessage();
        assertTrue(first.contains("sql=insert into t (id, name) values (?, ?)"), first);
        String second = appender.list.get(1).getFormattedMessage();
        assertTrue(second.contains("sql=select * from t where name = ? and id = ?"), second);
        assertFalse((first + second).contains("top-secret"), first + second);
        assertFalse((first + second).contains("secret"), first + second);
    }

    @Test
    void 素のStatementのバッチと引数なし実行はそれと分かるテンプレートで1回と数える() throws Exception {
        DbQueryRecorder.begin(60_000, 10);
        try (Connection c = wrapped.getConnection(); Statement s = c.createStatement()) {
            s.addBatch("insert into t (id, name) values (1, 'a')");
            s.addBatch("insert into t (id, name) values (2, 'b')");
            assertEquals(2, s.executeBatch().length);
            s.addBatch("insert into t (id, name) values (3, 'c')");
            assertEquals(1, s.executeLargeBatch().length);
            assertEquals(1L, s.executeLargeUpdate("insert into t (id, name) values (4, 'd')"));
            assertFalse(s.execute("delete from t where id = 99"));
        }

        assertEquals(4, DbQueryRecorder.end().queries());
    }

    @Test
    void バインド値はWARNに出ない() throws Exception {
        DbQueryRecorder.begin(0, 10);
        try (Connection c = wrapped.getConnection();
             PreparedStatement ps = c.prepareStatement("select name from t where name = ?")) {
            ps.setString(1, "bind-secret-value");
            ps.executeQuery().close();
        }
        DbQueryRecorder.end();

        assertEquals(1, appender.list.size());
        assertEquals(Level.WARN, appender.list.get(0).getLevel());
        String message = appender.list.get(0).getFormattedMessage();
        assertTrue(message.contains("sql=select name from t where name = ?"), message);
        assertFalse(message.contains("bind-secret-value"), message);
    }

    @Test
    void SQLの空白は畳んで同じテンプレートとして数える() throws Exception {
        DbQueryRecorder.begin(60_000, 2);
        try (Connection c = wrapped.getConnection()) {
            c.prepareStatement("select name\n   from t   where id = ?").close();
            for (String sql : new String[] {"select name\n   from t   where id = ?", "select name from t where id = ?"}) {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setInt(1, 1);
                    ps.executeQuery().close();
                }
            }
        }
        DbQueryRecorder.end();

        assertEquals(1, appender.list.size());
        assertTrue(appender.list.get(0).getFormattedMessage().contains("sql=select name from t where id = ?"));
    }

    @Test
    void 失敗したSQLもSQLExceptionのまま伝え回数には数える() throws Exception {
        DbQueryRecorder.begin(60_000, 10);
        try (Connection c = wrapped.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("insert into t (id, name) values (?, ?)")) {
                ps.setInt(1, 1);
                ps.setString(2, "a");
                ps.executeUpdate();
                assertThrows(SQLException.class, ps::executeUpdate);
            }
        }

        assertEquals(2, DbQueryRecorder.end().queries());
    }

    @Test
    void CallableStatementの実行も数える() throws Exception {
        DbQueryRecorder.begin(60_000, 10);
        try (Connection c = wrapped.getConnection(); CallableStatement cs = c.prepareCall("CALL 1")) {
            cs.execute();
        }

        assertEquals(1, DbQueryRecorder.end().queries());
    }

    @Test
    void 記録していない間のクエリは数えず例外にもならない() throws Exception {
        try (Connection c = wrapped.getConnection(); Statement s = c.createStatement()) {
            s.execute("select 1");
        }

        assertEquals(0, DbQueryRecorder.end().queries());
    }
}
