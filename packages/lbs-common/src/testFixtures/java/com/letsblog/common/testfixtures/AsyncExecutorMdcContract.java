package com.letsblog.common.testfixtures;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 各サービスの{@code AsyncJobConfig}が作る全{@link ThreadPoolTaskExecutor} Beanが、投入スレッドのMDC
 * (処理ID)を実行スレッドへ引き継ぎ、実行後に持ち越さないことの契約テスト(issue #1731)。
 * 引き継ぎの無いBeanが1つでもあれば失敗する。
 */
public final class AsyncExecutorMdcContract {

    private static final String KEY = "correlationId";

    private AsyncExecutorMdcContract() {
    }

    /** {@code configClass}の{@code @Bean}のうちExecutorを返す全メソッドを呼び、MDCの引き継ぎを検査する。 */
    public static void assertAllExecutorBeansPropagateMdc(Class<?> configClass) throws Exception {
        Object config = configClass.getDeclaredConstructor().newInstance();
        int checked = 0;
        for (Method method : configClass.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Bean.class) || !Executor.class.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            Object bean = method.invoke(config);
            check(bean instanceof ThreadPoolTaskExecutor,
                    configClass.getSimpleName() + "." + method.getName() + " は ThreadPoolTaskExecutor ではありません");
            ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) bean;
            try {
                assertPropagates(configClass.getSimpleName() + "." + method.getName(), pool);
            } finally {
                pool.shutdown();
                MDC.clear();
            }
            checked++;
        }
        check(checked > 0, configClass.getName() + " にExecutorを返す@Beanがありません");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertPropagates(String name, ThreadPoolTaskExecutor pool) throws Exception {
        MDC.put(KEY, "contract-id");
        List<Future<String>> withId = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            withId.add(pool.submit(() -> MDC.get(KEY)));
        }
        for (Future<String> f : withId) {
            String seen = f.get();
            check("contract-id".equals(seen),
                    name + ": 投入時のMDCの処理IDが実行スレッドへ引き継がれていません(TaskDecorator未設定。実際: " + seen + ")");
        }
        MDC.clear();
        List<Future<String>> withoutId = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            withoutId.add(pool.submit(() -> MDC.get(KEY)));
        }
        for (Future<String> f : withoutId) {
            String seen = f.get();
            check(seen == null, name + ": 前のタスクの処理IDが実行スレッドに残っています(実際: " + seen + ")");
        }
    }
}
