package com.letsblog.common.async;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 非同期ジョブの実行スレッドへ投入時のMDC(処理ID)を引き継ぎ、実行後に元へ戻す(issue #1731)。 */
@DisplayName("MdcPropagatingTaskDecorator: 投入時のMDCを実行スレッドへ引き継ぎ、実行後に戻す(issue #1731)")
class MdcPropagatingTaskDecoratorTest {

    private final AtomicReference<Map<String, String>> seenInside = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> seenAfter = new AtomicReference<>();
    private final AtomicReference<String> insideStale = new AtomicReference<>();
    private ThreadPoolTaskExecutor executor;

    @AfterEach
    void tearDown() {
        MDC.clear();
        if (executor != null) {
            executor.shutdown();
        }
    }

    private ThreadPoolTaskExecutor singleThreadExecutor() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setTaskDecorator(new MdcPropagatingTaskDecorator());
        executor.initialize();
        return executor;
    }

    @Test
    @DisplayName("投入時のMDCの全キーが、実行中のタスクから読める")
    void propagatesSubmitterMdc() throws Exception {
        ThreadPoolTaskExecutor pool = singleThreadExecutor();
        MDC.put("correlationId", "req-1");
        MDC.put("other", "x");

        Map<String, String> seen = pool.submit(MDC::getCopyOfContextMap).get();

        assertThat(seen).containsEntry("correlationId", "req-1").containsEntry("other", "x");
    }

    @Test
    @DisplayName("実行後は実行スレッドのMDCが空に戻り、次のタスクへ持ち越されない")
    void restoresWorkerMdcAfterRun() throws Exception {
        ThreadPoolTaskExecutor pool = singleThreadExecutor();
        MDC.put("correlationId", "req-1");
        pool.submit(() -> { }).get();
        MDC.clear();

        String next = pool.submit(() -> MDC.get("correlationId")).get();

        assertThat(next).isNull();
    }

    @Test
    @DisplayName("実行スレッドが元から持っていたMDCは、タスク中は投入元のものに置き換わり、後に復元される")
    void restoresPreviousWorkerMdc() throws Exception {
        MDC.put("correlationId", "req-2");
        Runnable decorated = new MdcPropagatingTaskDecorator().decorate(() -> {
            seenInside.set(MDC.getCopyOfContextMap());
        });
        MDC.clear();

        Thread worker = new Thread(() -> {
            MDC.put("worker", "w");
            decorated.run();
            seenAfter.set(MDC.getCopyOfContextMap());
        });
        worker.start();
        worker.join();

        assertThat(seenInside.get()).containsEntry("correlationId", "req-2").doesNotContainKey("worker");
        assertThat(seenAfter.get()).containsEntry("worker", "w").doesNotContainKey("correlationId");
    }

    @Test
    @DisplayName("タスクが例外で終わっても、実行スレッドのMDCは戻る")
    void restoresMdcWhenTaskThrows() throws Exception {
        ThreadPoolTaskExecutor pool = singleThreadExecutor();
        MDC.put("correlationId", "req-3");

        assertThatThrownBy(() -> pool.submit(() -> {
            throw new IllegalStateException("boom");
        }).get()).isInstanceOf(ExecutionException.class);
        MDC.clear();

        assertThat(pool.submit(() -> MDC.get("correlationId")).get()).isNull();
    }

    @Test
    @DisplayName("投入元のMDCが空なら、タスク中は実行スレッドの残りが見えず、後に復元される")
    void emptySubmitterMdcClearsWorker() throws Exception {
        MDC.clear();
        Runnable decorated = new MdcPropagatingTaskDecorator().decorate(() -> {
            seenInside.set(MDC.getCopyOfContextMap());
            insideStale.set(MDC.get("stale"));
        });

        Thread worker = new Thread(() -> {
            MDC.put("stale", "s");
            decorated.run();
            seenAfter.set(MDC.getCopyOfContextMap());
        });
        worker.start();
        worker.join();

        assertThat(insideStale.get()).isNull();
        assertThat(seenAfter.get()).containsEntry("stale", "s");
    }
}
