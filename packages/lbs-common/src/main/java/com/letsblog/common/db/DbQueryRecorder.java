package com.letsblog.common.db;

import com.letsblog.common.web.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

/**
 * 1リクエストの間に実行したDBクエリの回数・合計時間を数え、遅いクエリと同じSQLの繰り返し(N+1)を
 * WARNで出す(issue #1736)。リクエストを処理するスレッドのThreadLocalに持つ。
 * {@code RequestDurationLoggingFilter}が{@link #begin}/{@link #end}で囲み、
 * {@link DbQueryMetricsDataSourcePostProcessor}が包んだJDBCが{@link #record}を呼ぶ。
 *
 * <p>ログに出すのはSQLの<b>テンプレート</b>だけ。バインド値はこのクラスへ渡ってこない
 * (PreparedStatementのSQL文字列だけを受け取る)ので、値が出る経路がない。
 *
 * <p>非同期ジョブ・タイマーなど{@link #begin}されていないスレッドの呼び出しは数えない(#1736のスコープ外)。
 */
public final class DbQueryRecorder {

    /**
     * 単一クエリをWARNにする既定の閾値(ミリ秒)。リクエスト全体の既定WARN閾値(1000ms)の半分で、
     * 1本のクエリがリクエストの遅さの主因になり得る水準。通常の主キー・索引検索は数ms〜数十msなので、
     * 500msを超えるのは索引漏れ・全表走査・ロック待ちの疑いが強い。
     */
    public static final long DEFAULT_SLOW_QUERY_THRESHOLD_MS = 500L;

    /**
     * 同じSQLテンプレートの繰り返しをWARNにする既定の回数。一覧画面で数件の関連を引くのは正常で、
     * 画面の1ページが数十件に達するまではN+1の被害が小さい。10回なら正常な少数回の再実行
     * (リトライや2〜3件の関連取得)を拾わず、ページ件数に比例して増える典型的なN+1だけが掛かる。
     */
    public static final int DEFAULT_REPEAT_THRESHOLD = 10;

    private static final Logger log = LoggerFactory.getLogger(DbQueryRecorder.class);
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final ThreadLocal<Recording> CURRENT = new ThreadLocal<>();

    private DbQueryRecorder() {
    }

    /** 1リクエスト分の集計。 */
    public record Summary(int queries, long totalMs) {
    }

    private static final class Recording {
        private final long slowQueryThresholdMs;
        private final int repeatThreshold;
        private final Map<String, Integer> countsByTemplate = new HashMap<>();
        private int queries;
        private long totalNanos;

        private Recording(long slowQueryThresholdMs, int repeatThreshold) {
            this.slowQueryThresholdMs = slowQueryThresholdMs;
            this.repeatThreshold = repeatThreshold;
        }
    }

    /** 閾値が不正なら例外。{@link #begin}と、閾値を保持するフィルタの生成時の両方で使う。 */
    public static void validate(long slowQueryThresholdMs, int repeatThreshold) {
        if (slowQueryThresholdMs < 0) {
            throw new IllegalArgumentException(
                    "slowQueryThresholdMs must not be negative: " + slowQueryThresholdMs);
        }
        if (repeatThreshold < 1) {
            throw new IllegalArgumentException("repeatThreshold must be at least 1: " + repeatThreshold);
        }
    }

    /** このスレッドの記録を始める。前の記録が残っていれば捨てる。 */
    public static void begin(long slowQueryThresholdMs, int repeatThreshold) {
        validate(slowQueryThresholdMs, repeatThreshold);
        CURRENT.set(new Recording(slowQueryThresholdMs, repeatThreshold));
    }

    /** 1回のクエリ実行を記録する。{@link #begin}されていなければ何もしない。 */
    public static void record(String sqlTemplate, long durationNanos) {
        Recording recording = CURRENT.get();
        if (recording == null) {
            return;
        }
        recording.queries++;
        recording.totalNanos += durationNanos;
        recording.countsByTemplate.merge(sqlTemplate, 1, Integer::sum);
        if (durationNanos > recording.slowQueryThresholdMs * NANOS_PER_MILLI) {
            log.warn("slow db query: duration_ms={} threshold_ms={} correlation_id={} sql={}",
                    durationNanos / NANOS_PER_MILLI, recording.slowQueryThresholdMs, correlationId(), sqlTemplate);
        }
    }

    /**
     * 記録を終えて集計を返す。同じテンプレートが閾値の回数以上実行されていれば、テンプレートごとに
     * WARNを1行出す(回数が確定する終了時に出すので、繰り返しの途中で何行も出ない)。
     */
    public static Summary end() {
        Recording recording = CURRENT.get();
        CURRENT.remove();
        if (recording == null) {
            return new Summary(0, 0);
        }
        recording.countsByTemplate.forEach((sql, count) -> {
            if (count >= recording.repeatThreshold) {
                log.warn("repeated db query: count={} threshold={} correlation_id={} sql={}",
                        count, recording.repeatThreshold, correlationId(), sql);
            }
        });
        return new Summary(recording.queries, recording.totalNanos / NANOS_PER_MILLI);
    }

    private static String correlationId() {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        return correlationId == null || correlationId.isBlank() ? "-" : correlationId;
    }
}
