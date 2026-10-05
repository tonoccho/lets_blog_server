package com.letsblog.common.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * リクエスト所要時間ログフィルタの単体テスト(issue #1470)。時計を差し替えて、閾値の境界を
 * 実時間に依存せず検証する。
 */
class RequestDurationLoggingFilterTest {

    private static final long MS = 1_000_000L;

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestDurationLoggingFilter.class);
    private final AtomicLong nanoClock = new AtomicLong();

    @BeforeEach
    void attachAppender() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        MDC.clear();
    }

    private RequestDurationLoggingFilter filter(long thresholdMs) {
        return new RequestDurationLoggingFilter(thresholdMs, nanoClock::get);
    }

    private FilterChain chainTakingMillis(long millis, int status) {
        return (req, res) -> {
            nanoClock.addAndGet(millis * MS);
            ((MockHttpServletResponse) res).setStatus(status);
        };
    }

    private void run(RequestDurationLoggingFilter filter, String method, String path, FilterChain chain)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        filter.doFilter(request, new MockHttpServletResponse(), chain);
    }

    @Test
    void リクエスト1件につきmethod_path_status_所要時間_相関IDを含む1行を出す() throws Exception {
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-1");

        run(filter(1000), "GET", "/api/posts", chainTakingMillis(42, 200));

        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.INFO, event.getLevel());
        assertEquals("service request: method=GET path=/api/posts status=200 duration_ms=42 correlation_id=corr-1",
                event.getFormattedMessage());
    }

    @Test
    void 相関IDがMDCに無ければハイフンを出す() throws Exception {
        run(filter(1000), "POST", "/api/posts", chainTakingMillis(1, 201));

        assertTrue(appender.list.get(0).getFormattedMessage().endsWith("correlation_id=-"),
                appender.list.get(0).getFormattedMessage());
    }

    @Test
    void 閾値ちょうどはINFOで閾値を超えるとWARNになる() throws Exception {
        run(filter(100), "GET", "/api/a", chainTakingMillis(100, 200));
        run(filter(100), "GET", "/api/b", chainTakingMillis(101, 200));

        assertEquals(Level.INFO, appender.list.get(0).getLevel());
        assertEquals(Level.WARN, appender.list.get(1).getLevel());
        assertTrue(appender.list.get(1).getFormattedMessage().contains("duration_ms=101"));
        assertTrue(appender.list.get(1).getFormattedMessage().contains("slow_threshold_ms=100"),
                "WARNには閾値も載せ、なぜWARNかをログだけで分かるようにする");
    }

    @Test
    void 閾値が負の値なら生成できない() {
        assertThrows(IllegalArgumentException.class, () -> new RequestDurationLoggingFilter(-1));
    }

    @Test
    void 公開コンストラクタは実時計で動く() throws Exception {
        run(new RequestDurationLoggingFilter(10_000), "GET", "/api/x", (req, res) -> { });

        assertEquals(1, appender.list.size());
    }

    @Test
    void actuatorとstreamはログに出さない() throws Exception {
        run(filter(0), "GET", "/actuator/health", chainTakingMillis(5, 200));
        run(filter(0), "GET", "/actuator", chainTakingMillis(5, 200));
        run(filter(0), "GET", "/api/jobs/1/stream", chainTakingMillis(5, 200));

        assertTrue(appender.list.isEmpty(), "ヘルスチェックのポーリングやSSEでログを埋めない");
    }

    @Test
    void 例外で終わるリクエストは500として所要時間を出し例外はそのまま伝える() {
        FilterChain failing = (req, res) -> {
            nanoClock.addAndGet(7 * MS);
            throw new ServletException("boom");
        };

        assertThrows(ServletException.class, () -> run(filter(1000), "GET", "/api/boom", failing));

        assertEquals(1, appender.list.size());
        assertTrue(appender.list.get(0).getFormattedMessage().contains("status=500 duration_ms=7"),
                appender.list.get(0).getFormattedMessage());
    }

    @Test
    void 順序は相関IDフィルタの直後でSpringSecurityより前() {
        Integer own = OrderUtils.getOrder(RequestDurationLoggingFilter.class);
        Integer correlation = OrderUtils.getOrder(CorrelationIdFilter.class);

        assertNotNull(own);
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 1, own.intValue());
        assertTrue(own > correlation, "相関IDがMDCに入った後で動き、ログ行に相関IDが載る");
        assertTrue(own < -100, "Spring Securityのフィルタチェーン(-100)より前で、401/403も計測する");
    }
}
