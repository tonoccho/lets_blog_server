package com.letsblog.common.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.letsblog.common.client.SyncCallProfile;
import com.letsblog.common.client.SyncServiceClient;
import com.letsblog.common.web.CorrelationIdFilter;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.web.client.RestClient;

/**
 * タイマー起動の処理に実行ごとの処理IDを採番し、完了行を出すスケジューラの単体テスト(issue #1733)。
 * 本物のスケジューラの代わりに、渡されたRunnableを記録するだけの委譲先を使い、実時間に依存しない。
 */
class ScheduledTaskCorrelationSchedulerTest {

    private static final long MS = 1_000_000L;

    private final ListAppender<ILoggingEvent> completionAppender = new ListAppender<>();
    private final ListAppender<ILoggingEvent> bodyAppender = new ListAppender<>();
    private final Logger completionLogger = (Logger) LoggerFactory.getLogger(ScheduledTaskCorrelationScheduler.class);
    private final Logger bodyLogger = (Logger) LoggerFactory.getLogger(Sample.class);
    private final AtomicLong nanoClock = new AtomicLong();
    private final RecordingScheduler delegate = new RecordingScheduler();
    private final ScheduledTaskCorrelationScheduler scheduler =
            new ScheduledTaskCorrelationScheduler(delegate, nanoClock::get);
    private Level originalLevel;
    private HttpServer httpServer;

    @BeforeEach
    void attach() {
        originalLevel = completionLogger.getLevel();
        completionLogger.setLevel(Level.DEBUG);
        completionAppender.start();
        bodyAppender.start();
        completionLogger.addAppender(completionAppender);
        bodyLogger.addAppender(bodyAppender);
    }

    @AfterEach
    void detach() {
        completionLogger.detachAppender(completionAppender);
        bodyLogger.detachAppender(bodyAppender);
        completionLogger.setLevel(originalLevel);
        MDC.clear();
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    /** 本物の{@code @Scheduled}メソッドと同じく、対象オブジェクトとメソッドから作るRunnable。 */
    private static ScheduledMethodRunnable sampleTask(Sample sample) throws NoSuchMethodException {
        return new ScheduledMethodRunnable(sample, "tick");
    }

    @Test
    void 実行中のログ行に処理IDがあり_連続する2回の実行で異なる() throws Exception {
        scheduler.scheduleWithFixedDelay(sampleTask(new Sample(nanoClock, 1, false)), Duration.ofSeconds(15));

        delegate.runLast();
        delegate.runLast();

        assertEquals(2, bodyAppender.list.size());
        String first = bodyAppender.list.get(0).getMDCPropertyMap().get(CorrelationIdFilter.MDC_KEY);
        String second = bodyAppender.list.get(1).getMDCPropertyMap().get(CorrelationIdFilter.MDC_KEY);
        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first, second);
        assertEquals(36, first.length(), "UUID形式");
    }

    @Test
    void 完了行は実行中と同じ処理IDを持ち_終了後はMDCから消える() throws Exception {
        scheduler.scheduleWithFixedDelay(sampleTask(new Sample(nanoClock, 1, false)), Duration.ofSeconds(15));

        delegate.runLast();

        String bodyId = bodyAppender.list.get(0).getMDCPropertyMap().get(CorrelationIdFilter.MDC_KEY);
        assertEquals(bodyId, completionAppender.list.get(0).getMDCPropertyMap().get(CorrelationIdFilter.MDC_KEY));
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    @Test
    void 周期1分未満のタスクの成功はDEBUGで_本文にkey_valueの完了行を出す() throws Exception {
        scheduler.scheduleWithFixedDelay(sampleTask(new Sample(nanoClock, 250, false)), Duration.ofSeconds(15));

        delegate.runLast();

        assertEquals(1, completionAppender.list.size());
        ILoggingEvent event = completionAppender.list.get(0);
        assertEquals(Level.DEBUG, event.getLevel());
        assertEquals("scheduled task completed: trigger=scheduled task=Sample#tick duration_ms=250 outcome=success",
                event.getFormattedMessage());
    }

    @Test
    void 周期が1分以上または不明のタスクの成功はINFO() throws Exception {
        scheduler.scheduleWithFixedDelay(sampleTask(new Sample(nanoClock, 1, false)), Duration.ofSeconds(60));
        delegate.runLast();
        scheduler.schedule(sampleTask(new Sample(nanoClock, 1, false)), new CronTrigger("0 0 2 * * *"));
        delegate.runLast();

        assertEquals(List.of(Level.INFO, Level.INFO),
                completionAppender.list.stream().map(ILoggingEvent::getLevel).toList());
    }

    @Test
    void 失敗はWARNでoutcome_failureと例外クラス名を出し_例外は呼び出し元へ再送出する() throws Exception {
        scheduler.scheduleAtFixedRate(sampleTask(new Sample(nanoClock, 7, true)), Duration.ofSeconds(15));

        assertThrows(IllegalStateException.class, delegate::runLast);

        ILoggingEvent event = completionAppender.list.get(0);
        assertEquals(Level.WARN, event.getLevel());
        assertEquals("scheduled task completed: trigger=scheduled task=Sample#tick duration_ms=7 outcome=failure"
                + " error=IllegalStateException", event.getFormattedMessage());
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    @Test
    void 周期1分以上のタスクの失敗もWARN() throws Exception {
        scheduler.scheduleWithFixedDelay(sampleTask(new Sample(nanoClock, 1, true)), Duration.ofMinutes(5));

        assertThrows(IllegalStateException.class, delegate::runLast);

        assertEquals(Level.WARN, completionAppender.list.get(0).getLevel());
    }

    @Test
    void ScheduledMethodRunnableでないRunnableはクラス名をタスク名にする() {
        Runnable plain = () -> { };
        scheduler.schedule(plain, Instant.EPOCH);

        delegate.runLast();

        String message = completionAppender.list.get(0).getFormattedMessage();
        assertTrue(message.contains("task=" + plain.getClass().getName() + " "), message);
    }

    @Test
    void 全てのschedule系メソッドが委譲先へ渡り_周期から成功時の水準が決まる() {
        Runnable noop = () -> { };
        Instant start = Instant.EPOCH;
        Duration fast = Duration.ofSeconds(10);
        Trigger trigger = new CronTrigger("0 0 2 * * *");

        scheduler.schedule(noop, trigger);
        scheduler.schedule(noop, start);
        scheduler.scheduleAtFixedRate(noop, start, fast);
        scheduler.scheduleAtFixedRate(noop, fast);
        scheduler.scheduleWithFixedDelay(noop, start, fast);
        scheduler.scheduleWithFixedDelay(noop, fast);

        assertEquals(6, delegate.scheduled.size());
        assertEquals(List.of(Level.INFO, Level.INFO, Level.DEBUG, Level.DEBUG, Level.DEBUG, Level.DEBUG),
                runAll().stream().map(ILoggingEvent::getLevel).toList());
        assertSame(trigger, delegate.lastTrigger);
        assertEquals(start, delegate.lastStart);
        assertEquals(fast, delegate.lastPeriod);
    }

    @Test
    void 時計は委譲先のものを使う() {
        // 委譲先の既定実装は呼ぶたびに新しいClockを作るので、同一性ではなく等価で比べる。
        assertEquals(delegate.getClock(), scheduler.getClock());
    }

    @Test
    void タイマー起動の処理からSyncServiceClientで呼ぶと_要求にその実行の処理IDが付く() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/", exchange -> {
            received.set(exchange.getRequestHeaders().getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER));
            byte[] body = "{\"value\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        httpServer.start();
        SyncServiceClient client = SyncServiceClient.builder(RestClient.builder(), "test-service",
                "http://127.0.0.1:" + httpServer.getAddress().getPort())
                .profile(SyncCallProfile.SHORT).build();
        AtomicReference<String> idInTask = new AtomicReference<>();
        Runnable task = () -> {
            idInTask.set(MDC.get(CorrelationIdFilter.MDC_KEY));
            client.get("/api/value", new Object[0], Value.class, h -> { });
        };

        scheduler.scheduleWithFixedDelay(task, Duration.ofSeconds(15));
        delegate.runLast();

        assertNotNull(idInTask.get());
        assertEquals(idInTask.get(), received.get());
    }

    private List<ILoggingEvent> runAll() {
        delegate.scheduled.forEach(Runnable::run);
        return completionAppender.list;
    }

    record Value(String value) { }

    /** {@code @Scheduled}メソッドを持つ対象を模す。本文で1行ログを出し、時計を進める。 */
    static class Sample {
        private final org.slf4j.Logger log = LoggerFactory.getLogger(Sample.class);
        private final AtomicLong clock;
        private final long millis;
        private final boolean fail;

        Sample(AtomicLong clock, long millis, boolean fail) {
            this.clock = clock;
            this.millis = millis;
            this.fail = fail;
        }

        public void tick() {
            log.info("working");
            clock.addAndGet(millis * MS);
            if (fail) {
                throw new IllegalStateException("boom");
            }
        }
    }

    /** 渡されたRunnableと周期を記録するだけのTaskScheduler。 */
    static class RecordingScheduler implements TaskScheduler {
        final List<Runnable> scheduled = new ArrayList<>();
        Trigger lastTrigger;
        Instant lastStart;
        Duration lastPeriod;

        void runLast() {
            scheduled.get(scheduled.size() - 1).run();
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            scheduled.add(task);
            lastTrigger = trigger;
            return null;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            scheduled.add(task);
            lastStart = startTime;
            return null;
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            scheduled.add(task);
            lastStart = startTime;
            lastPeriod = period;
            return null;
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            scheduled.add(task);
            lastPeriod = period;
            return null;
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
            scheduled.add(task);
            lastStart = startTime;
            lastPeriod = delay;
            return null;
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            scheduled.add(task);
            lastPeriod = delay;
            return null;
        }
    }
}
