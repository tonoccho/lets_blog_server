package com.letsblog.common.db;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** リクエスト単位のDBクエリ記録の単体テスト(issue #1736)。 */
class DbQueryRecorderTest {

    private static final long MS = 1_000_000L;
    private static final String SQL = "select * from posts where id = ?";

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(DbQueryRecorder.class);

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
        DbQueryRecorder.end();
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        DbQueryRecorder.end();
        MDC.clear();
    }

    @Test
    void 回数と合計ミリ秒を数える() {
        DbQueryRecorder.begin(500, 10);
        DbQueryRecorder.record(SQL, 3 * MS);
        DbQueryRecorder.record("select 1", 3 * MS);

        DbQueryRecorder.Summary summary = DbQueryRecorder.end();

        assertEquals(2, summary.queries());
        assertEquals(6, summary.totalMs());
        assertTrue(appender.list.isEmpty());
    }

    @Test
    void 記録が始まっていなければ何も数えない() {
        DbQueryRecorder.record(SQL, 3 * MS);

        DbQueryRecorder.Summary summary = DbQueryRecorder.end();

        assertEquals(0, summary.queries());
        assertEquals(0, summary.totalMs());
    }

    @Test
    void endで記録は消え次のリクエストに持ち越さない() {
        DbQueryRecorder.begin(500, 10);
        DbQueryRecorder.record(SQL, MS);
        DbQueryRecorder.end();

        assertEquals(0, DbQueryRecorder.end().queries());
    }

    @Test
    void beginは前の記録を捨てて数え直す() {
        DbQueryRecorder.begin(500, 10);
        DbQueryRecorder.record(SQL, MS);
        DbQueryRecorder.begin(500, 10);

        assertEquals(0, DbQueryRecorder.end().queries());
    }

    @Test
    void 閾値を超えた単一クエリはSQLテンプレートつきのWARNを1行出す() {
        MDC.put("correlationId", "corr-9");
        DbQueryRecorder.begin(500, 10);

        DbQueryRecorder.record(SQL, 501 * MS);

        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.WARN, event.getLevel());
        String message = event.getFormattedMessage();
        assertTrue(message.startsWith("slow db query:"), message);
        assertTrue(message.contains("duration_ms=501"), message);
        assertTrue(message.contains("threshold_ms=500"), message);
        assertTrue(message.contains("correlation_id=corr-9"), message);
        assertTrue(message.contains("sql=" + SQL), message);
    }

    @Test
    void 閾値ちょうどのクエリはWARNにしない() {
        DbQueryRecorder.begin(500, 10);

        DbQueryRecorder.record(SQL, 500 * MS);

        assertTrue(appender.list.isEmpty());
    }

    @Test
    void 相関IDがMDCに無ければハイフンを出す() {
        DbQueryRecorder.begin(0, 10);

        DbQueryRecorder.record(SQL, MS);

        assertTrue(appender.list.get(0).getFormattedMessage().contains("correlation_id=-"));
    }

    @Test
    void 相関IDが空白ならハイフンを出す() {
        MDC.put("correlationId", " ");
        DbQueryRecorder.begin(0, 10);

        DbQueryRecorder.record(SQL, MS);

        assertTrue(appender.list.get(0).getFormattedMessage().contains("correlation_id=-"));
    }

    @Test
    void 同じSQLテンプレートが閾値の回数に達したらendでWARNを1行出す() {
        DbQueryRecorder.begin(500, 3);
        for (int i = 0; i < 3; i++) {
            DbQueryRecorder.record(SQL, MS);
        }
        assertTrue(appender.list.isEmpty(), "終わるまでは出さない(回数が確定してから1行で出す)");

        DbQueryRecorder.end();

        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.WARN, event.getLevel());
        String message = event.getFormattedMessage();
        assertTrue(message.startsWith("repeated db query:"), message);
        assertTrue(message.contains("count=3"), message);
        assertTrue(message.contains("threshold=3"), message);
        assertTrue(message.contains("sql=" + SQL), message);
    }

    @Test
    void 閾値に満たない繰り返しはWARNにしない() {
        DbQueryRecorder.begin(500, 3);
        DbQueryRecorder.record(SQL, MS);
        DbQueryRecorder.record(SQL, MS);

        DbQueryRecorder.end();

        assertTrue(appender.list.isEmpty());
    }

    @Test
    void 閾値を大きく超えても同じテンプレートのWARNは1行だけで別テンプレートは別に出る() {
        DbQueryRecorder.begin(500, 3);
        for (int i = 0; i < 7; i++) {
            DbQueryRecorder.record(SQL, MS);
            DbQueryRecorder.record("select * from tags where post_id = ?", MS);
        }
        DbQueryRecorder.record("select 1", MS);

        DbQueryRecorder.end();

        assertEquals(2, appender.list.size());
        assertTrue(appender.list.get(0).getFormattedMessage().contains("count=7"));
        assertTrue(appender.list.get(1).getFormattedMessage().contains("count=7"));
    }

    @Test
    void 閾値は不正な値を受け付けない() {
        assertThrows(IllegalArgumentException.class, () -> DbQueryRecorder.begin(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> DbQueryRecorder.begin(500, 0));
        assertThrows(IllegalArgumentException.class, () -> DbQueryRecorder.validate(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> DbQueryRecorder.validate(500, 0));
        DbQueryRecorder.validate(0, 1);
    }
}
