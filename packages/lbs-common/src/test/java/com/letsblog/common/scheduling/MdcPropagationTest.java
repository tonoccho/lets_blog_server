package com.letsblog.common.scheduling;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 投入時のMDC(処理ID)を実行スレッドへ引き継ぎ、終了後に実行スレッドのMDCを元へ戻す(issue #1732)。 */
class MdcPropagationTest {

    private static final String KEY = "correlationId";

    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        MDC.clear();
        pool.shutdownNow();
    }

    private <T> T onPool(Callable<T> task) throws Exception {
        return pool.submit(task).get(5, TimeUnit.SECONDS);
    }

    @Test
    void runnable_投入時のMDCを実行中に見せ_終了後は元に戻す() throws Exception {
        onPool(() -> {
            MDC.put("stale", "x");
            return null;
        });
        MDC.put(KEY, "cid-1");
        AtomicReference<String> seen = new AtomicReference<>();
        AtomicReference<String> staleSeen = new AtomicReference<>();

        pool.submit(MdcPropagation.runnable(() -> {
            seen.set(MDC.get(KEY));
            staleSeen.set(MDC.get("stale"));
        })).get(5, TimeUnit.SECONDS);

        assertEquals("cid-1", seen.get());
        assertNull(staleSeen.get(), "実行スレッドの元のMDCは投入元のMDCに置き換わる");
        assertEquals("x", onPool(() -> MDC.get("stale")), "終了後は実行スレッドの元のMDCへ戻る");
        assertNull(onPool(() -> MDC.get(KEY)));
    }

    @Test
    void runnable_投入元のMDCが空なら実行中は空で_終了後は実行スレッドの元のMDCへ戻る() throws Exception {
        MDC.clear();
        AtomicReference<String> staleSeen = new AtomicReference<>("unset");
        Runnable wrapped = MdcPropagation.runnable(() -> staleSeen.set(MDC.get("stale")));
        onPool(() -> {
            MDC.put("stale", "x");
            return null;
        });

        pool.submit(wrapped).get(5, TimeUnit.SECONDS);

        assertNull(staleSeen.get());
        assertEquals("x", onPool(() -> MDC.get("stale")));
    }

    @Test
    void runnable_元のMDCが無かった実行スレッドは終了後にMDCを空へ戻す() throws Exception {
        MDC.put(KEY, "cid-2");

        pool.submit(MdcPropagation.runnable(() -> { })).get(5, TimeUnit.SECONDS);

        assertNull(onPool(() -> MDC.get(KEY)));
    }

    @Test
    void runnable_失敗しても終了後にMDCを元へ戻し_例外はそのまま伝わる() throws Exception {
        MDC.put(KEY, "cid-3");
        IllegalStateException boom = new IllegalStateException("boom");

        Future<?> future = pool.submit(MdcPropagation.runnable(() -> {
            throw boom;
        }));
        ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));

        assertEquals(boom, e.getCause());
        assertNull(onPool(() -> MDC.get(KEY)));
    }

    @Test
    void 同じラップ済みRunnableを繰り返し実行しても毎回投入時のMDCを設定して戻す() throws Exception {
        MDC.put(KEY, "cid-loop");
        List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        Runnable wrapped = MdcPropagation.runnable(() -> seen.add(MDC.get(KEY)));
        MDC.clear();

        pool.submit(wrapped).get(5, TimeUnit.SECONDS);
        assertNull(onPool(() -> MDC.get(KEY)));
        pool.submit(wrapped).get(5, TimeUnit.SECONDS);

        assertEquals(List.of("cid-loop", "cid-loop"), seen);
        assertNull(onPool(() -> MDC.get(KEY)));
    }

    @Test
    void supplier_投入時のMDCで実行し結果を返し_終了後は元に戻す() throws Exception {
        MDC.put(KEY, "cid-4");

        String result = CompletableFuture.supplyAsync(MdcPropagation.supplier(() -> MDC.get(KEY)), pool)
                .get(5, TimeUnit.SECONDS);

        assertEquals("cid-4", result);
        assertNull(onPool(() -> MDC.get(KEY)));
    }

    @Test
    void callable_投入時のMDCで実行し結果を返し_終了後は元に戻す() throws Exception {
        MDC.put(KEY, "cid-5");

        String result = pool.submit(MdcPropagation.callable(() -> MDC.get(KEY))).get(5, TimeUnit.SECONDS);

        assertEquals("cid-5", result);
        assertNull(onPool(() -> MDC.get(KEY)));
    }

    @Test
    void executor_executeの呼び出し時点のMDCを引き継ぐ() throws Exception {
        MDC.put(KEY, "cid-6");
        AtomicReference<String> seen = new AtomicReference<>();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);

        MdcPropagation.executor(pool).execute(() -> {
            seen.set(MDC.get(KEY));
            done.countDown();
        });

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("cid-6", seen.get());
        assertNull(onPool(() -> MDC.get(KEY)));
    }

    @Test
    void executorService_submitとinvokeAllのタスクが投入時のMDCを引き継ぎ_停止の操作は委譲する() throws Exception {
        ExecutorService inner = Executors.newSingleThreadExecutor();
        ExecutorService decorated = MdcPropagation.executorService(inner);
        MDC.put(KEY, "cid-7");

        assertEquals("cid-7", decorated.submit(() -> MDC.get(KEY)).get(5, TimeUnit.SECONDS));
        List<Future<String>> all = decorated.invokeAll(List.of(() -> MDC.get(KEY)));
        assertEquals("cid-7", all.get(0).get());

        assertFalse(decorated.isShutdown());
        decorated.shutdown();
        assertTrue(decorated.isShutdown());
        assertTrue(decorated.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(decorated.isTerminated());
        assertTrue(decorated.shutdownNow().isEmpty());
    }
}
