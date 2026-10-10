package com.letsblog.common.scheduling;

import org.slf4j.MDC;

import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 別スレッドで走るタスクへ、投入した側のMDC(処理ID {@code correlationId}など)を引き継ぐ道具(issue #1732)。
 *
 * <p>ラップした時点のMDCを捕捉し、タスクの実行中だけ実行スレッドへ設定して、終了時(例外でも)に
 * 実行スレッドが元々持っていたMDCへ戻す。共通のExecutor上で次のタスクへ処理IDが持ち越されない。
 * 元のMDCが空だったスレッドは空へ戻す({@link MDC#clear()})。ラップ済みのタスクを繰り返し実行
 * (定期実行のwatchdogなど)すると、実行のたびに捕捉済みのMDCを設定して戻す。
 */
public final class MdcPropagation {

    private MdcPropagation() {
    }

    public static Runnable runnable(Runnable task) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                set(captured);
                task.run();
            } finally {
                set(previous);
            }
        };
    }

    public static <T> Supplier<T> supplier(Supplier<T> task) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                set(captured);
                return task.get();
            } finally {
                set(previous);
            }
        };
    }

    public static <T> Callable<T> callable(Callable<T> task) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                set(captured);
                return task.call();
            } finally {
                set(previous);
            }
        };
    }

    /** {@code execute}を呼んだ時点のMDCを、実行されるタスクへ引き継ぐ{@link Executor}。 */
    public static Executor executor(Executor delegate) {
        return command -> delegate.execute(runnable(command));
    }

    /** 投入された全タスクへ投入時のMDCを引き継ぐ{@link ExecutorService}。停止の操作は{@code delegate}へ委譲する。 */
    public static ExecutorService executorService(ExecutorService delegate) {
        return new PropagatingExecutorService(delegate);
    }

    private static void set(Map<String, String> contextMap) {
        if (contextMap == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(contextMap);
        }
    }

    private static final class PropagatingExecutorService extends AbstractExecutorService {
        private final ExecutorService delegate;

        PropagatingExecutorService(ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(runnable(command));
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
