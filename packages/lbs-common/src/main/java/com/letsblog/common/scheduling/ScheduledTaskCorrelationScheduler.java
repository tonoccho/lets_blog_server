package com.letsblog.common.scheduling;

import com.letsblog.common.web.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.util.ClassUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.function.LongSupplier;

/**
 * {@code @Scheduled}のタイマー起動の処理を、実行1回ごとに新しい処理ID(MDCの
 * {@value CorrelationIdFilter#MDC_KEY})で包み、完了行を1行出す{@link TaskScheduler}
 * (issue #1733)。実行中のログと、呼び出し先への{@code X-Correlation-Id}(SyncServiceClient、
 * RestClient、RabbitMQ)が同じ処理IDで続く。
 *
 * <p>完了行は本文に{@code key=value}で{@code trigger=scheduled task=クラス名#メソッド名
 * duration_ms=.. outcome=success|failure}を書く。失敗時は例外のクラス名だけを{@code error=}に載せる
 * (メッセージは秘密や改行を含みうるため出さない。スタックトレースは例外の再送出を受けたSpringが出す)。
 * 水準は、周期が1分未満と分かっている(fixedRate/fixedDelay)タスクの成功はDEBUG、失敗は常にWARN、
 * それ以外(周期1分以上・cron等)の成功はINFO。1分未満のタスクで毎回INFOを出して埋めないため。
 *
 * <p>例外は握りつぶさず再送出するので、Spring標準のエラー処理(周期タスクは次回も続行)は変わらない。
 * 各サービスは{@link ScheduledTaskCorrelationConfigurer}経由で使う。
 */
public class ScheduledTaskCorrelationScheduler implements TaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskCorrelationScheduler.class);
    private static final Duration QUIET_PERIOD_LIMIT = Duration.ofMinutes(1);
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final TaskScheduler delegate;
    private final LongSupplier nanoClock;

    public ScheduledTaskCorrelationScheduler(TaskScheduler delegate) {
        this(delegate, System::nanoTime);
    }

    /** テスト用: 時計を差し替えて所要時間を実時間に依存せず検証する。 */
    ScheduledTaskCorrelationScheduler(TaskScheduler delegate, LongSupplier nanoClock) {
        this.delegate = delegate;
        this.nanoClock = nanoClock;
    }

    @Override
    public Clock getClock() {
        return delegate.getClock();
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
        return delegate.schedule(wrap(task, null), trigger);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
        return delegate.schedule(wrap(task, null), startTime);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
        return delegate.scheduleAtFixedRate(wrap(task, period), startTime, period);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
        return delegate.scheduleAtFixedRate(wrap(task, period), period);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
        return delegate.scheduleWithFixedDelay(wrap(task, delay), startTime, delay);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
        return delegate.scheduleWithFixedDelay(wrap(task, delay), delay);
    }

    /** @param period 周期が分かるときだけ渡す(cron・1回限りはnull)。 */
    private Runnable wrap(Runnable task, Duration period) {
        String taskName = taskName(task);
        boolean quiet = period != null && period.compareTo(QUIET_PERIOD_LIMIT) < 0;
        return () -> runCorrelated(task, taskName, quiet);
    }

    private void runCorrelated(Runnable task, String taskName, boolean quiet) {
        MDC.put(CorrelationIdFilter.MDC_KEY, UUID.randomUUID().toString());
        long start = nanoClock.getAsLong();
        try {
            task.run();
            logCompletion(taskName, start, null, quiet);
        } catch (RuntimeException | Error e) {
            logCompletion(taskName, start, e, quiet);
            throw e;
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    private void logCompletion(String taskName, long start, Throwable failure, boolean quiet) {
        long durationMs = (nanoClock.getAsLong() - start) / NANOS_PER_MILLI;
        if (failure != null) {
            log.warn("scheduled task completed: trigger=scheduled task={} duration_ms={} outcome=failure error={}",
                    taskName, durationMs, failure.getClass().getSimpleName());
        } else if (quiet) {
            log.debug("scheduled task completed: trigger=scheduled task={} duration_ms={} outcome=success",
                    taskName, durationMs);
        } else {
            log.info("scheduled task completed: trigger=scheduled task={} duration_ms={} outcome=success",
                    taskName, durationMs);
        }
    }

    private static String taskName(Runnable task) {
        if (task instanceof ScheduledMethodRunnable methodRunnable) {
            return ClassUtils.getUserClass(methodRunnable.getTarget()).getSimpleName()
                    + "#" + methodRunnable.getMethod().getName();
        }
        return task.getClass().getName();
    }
}
