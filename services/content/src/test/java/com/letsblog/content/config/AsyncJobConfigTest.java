package com.letsblog.content.config;

import java.util.concurrent.Executor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** issue #1409: カスタムタグ生成ジョブ用の専用Executor。 */
@DisplayName("content-service: AsyncJobConfig(issue #1409)")
class AsyncJobConfigTest {

    @Test
    @DisplayName("@Async を有効にしている")
    void asyncEnabled() {
        assertNotNull(AsyncJobConfig.class.getAnnotation(EnableAsync.class));
    }

    @Test
    @DisplayName("customTagGenerationExecutor は LLM 待ちが主なので小さいプール(core 2 / max 4 / 待ち行列 20)")
    void customTagGenerationExecutorIsSmall() {
        Executor executor = new AsyncJobConfig().customTagGenerationExecutor();

        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
        assertEquals(2, pool.getCorePoolSize());
        assertEquals(4, pool.getMaxPoolSize());
        assertEquals(20, pool.getThreadPoolExecutor().getQueue().remainingCapacity());
        assertTrue(pool.getThreadNamePrefix().startsWith("custom-tag-generation"));
        pool.shutdown();
    }

    @Test
    @DisplayName("Bean 名は CustomTagGenerationJobRunner の @Async が指す名前と一致する")
    void beanNameMatchesAsyncQualifier() throws Exception {
        String beanName = AsyncJobConfig.class.getMethod("customTagGenerationExecutor")
                .getAnnotation(org.springframework.context.annotation.Bean.class).name()[0];
        String qualifier = com.letsblog.content.service.CustomTagGenerationJobRunner.class
                .getMethod("run", Long.class, com.letsblog.content.dto.GenerateCustomTagRequest.class)
                .getAnnotation(org.springframework.scheduling.annotation.Async.class).value();
        assertEquals(beanName, qualifier);
    }
}
