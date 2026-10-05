package com.letsblog.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.function.LongSupplier;

/**
 * リクエスト1件ごとに method / path / status / 所要時間 / 相関ID を1行で出すフィルタ(issue #1470)。
 * gatewayの{@code CorrelationIdWebFilter}が出す1行のServlet版で、下流サービス側の区間ごとの
 * 所要時間をログから追えるようにする。{@link CorrelationIdFilter}と同じく各サービスが
 * {@code @Bean}として登録する。
 *
 * <p><b>順序</b>: {@link Ordered#HIGHEST_PRECEDENCE}{@code + 1}。{@link CorrelationIdFilter}
 * ({@link Ordered#HIGHEST_PRECEDENCE})の<em>内側</em>で、Spring Securityのフィルタチェーン(-100)より
 * <em>前</em>に動く。外側に置くと、finallyで1行を出す時点で相関IDフィルタが既にMDCを消しており
 * 相関IDが載らないため。計測区間から外れるのは相関ID採番の数マイクロ秒だけで、認証失敗(401/403)を
 * 含む残りの処理はすべて計測に入る。
 *
 * <p><b>WARN閾値</b>: 所要時間が閾値を<em>超えた</em>(ちょうどは含まない)リクエストをWARNで出す。
 * 既定値は{@link #DEFAULT_SLOW_THRESHOLD_MS}。
 *
 * <p><b>出力しない経路</b>: {@code /actuator}配下と{@code /stream}で終わるパス(SSE)。
 * gatewayの{@code CorrelationIdWebFilter#isSkipLogging}と同じ。ヘルスチェックのポーリングで
 * ログを埋めず、長時間つながるストリームを「遅いリクエスト」として誤検知しないため。
 *
 * <p>リクエストボディ・クエリ文字列・ヘッダは出さない(認証情報・暗号化前トークンが流れる経路が
 * あるため。docs/LOGGING_AND_MONITORING.md参照)。
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestDurationLoggingFilter extends OncePerRequestFilter {

    /**
     * 既定のWARN閾値(ミリ秒)。gatewayアクセスログの実測では中央値が数十msで、外れ値が1秒を超える
     * (#1470)。1000msなら通常のリクエストをWARNにせず、利用者が「遅い」と感じ始める1秒超だけが
     * 拾える。LLM・レンダリング等の長い呼び出しは常にWARNになるが、それらは下流タイムアウト
     * (最大180s)まで含めて「遅さを見たい」対象なので意図どおり。
     */
    public static final long DEFAULT_SLOW_THRESHOLD_MS = 1000L;

    private static final Logger log = LoggerFactory.getLogger(RequestDurationLoggingFilter.class);
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final long slowThresholdMs;
    private final LongSupplier nanoClock;

    public RequestDurationLoggingFilter(long slowThresholdMs) {
        this(slowThresholdMs, System::nanoTime);
    }

    /** テスト用: 時計を差し替えて閾値の境界を実時間に依存せず検証する。 */
    RequestDurationLoggingFilter(long slowThresholdMs, LongSupplier nanoClock) {
        if (slowThresholdMs < 0) {
            throw new IllegalArgumentException("slowThresholdMs must not be negative: " + slowThresholdMs);
        }
        this.slowThresholdMs = slowThresholdMs;
        this.nanoClock = nanoClock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (isSkipLogging(path)) {
            filterChain.doFilter(request, response);
            return;
        }
        long start = nanoClock.getAsLong();
        int status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        try {
            filterChain.doFilter(request, response);
            status = response.getStatus();
        } finally {
            logRequest(request.getMethod(), path, status, (nanoClock.getAsLong() - start) / NANOS_PER_MILLI);
        }
    }

    private void logRequest(String method, String path, int status, long durationMs) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = "-";
        }
        if (durationMs > slowThresholdMs) {
            log.warn("service request: method={} path={} status={} duration_ms={} correlation_id={}"
                            + " slow_threshold_ms={}",
                    method, path, status, durationMs, correlationId, slowThresholdMs);
        } else {
            log.info("service request: method={} path={} status={} duration_ms={} correlation_id={}",
                    method, path, status, durationMs, correlationId);
        }
    }

    private static boolean isSkipLogging(String path) {
        return path.startsWith("/actuator") || path.endsWith("/stream");
    }
}
