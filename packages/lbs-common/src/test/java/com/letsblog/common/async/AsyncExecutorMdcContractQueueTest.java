package com.letsblog.common.async;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.letsblog.common.testfixtures.AsyncExecutorMdcContract;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 契約フィクスチャの投入がExecutorのキュー容量を超えないこと(issue #1789)。 */
class AsyncExecutorMdcContractQueueTest {

    /** キュー容量2・最大1: 完了を待たずに6件続けて投入すると拒否される。 */
    public static class TinyQueueConfig {
        @Bean
        public ThreadPoolTaskExecutor tinyExecutor() {
            ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
            e.setCorePoolSize(1);
            e.setMaxPoolSize(1);
            e.setQueueCapacity(2);
            e.setTaskDecorator(task -> {
                java.util.Map<String, String> ctx = org.slf4j.MDC.getCopyOfContextMap();
                return () -> {
                    if (ctx != null) {
                        org.slf4j.MDC.setContextMap(ctx);
                    }
                    try {
                        task.run();
                    } finally {
                        org.slf4j.MDC.clear();
                    }
                };
            });
            e.initialize();
            return e;
        }
    }

    @Test
    void contractDoesNotOverflowSmallQueue() {
        assertDoesNotThrow(() -> AsyncExecutorMdcContract.assertAllExecutorBeansPropagateMdc(TinyQueueConfig.class));
    }
}
