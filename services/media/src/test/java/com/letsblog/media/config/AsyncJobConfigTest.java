package com.letsblog.media.config;

import java.util.concurrent.Executor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 画像生成ジョブ専用のExecutor(issue #1405)。ComfyUIもホストGPUも1台なので並列度は1に絞り、
 * 溢れた要求は待ち行列で有限に受ける(無制限に積まない)。
 */
@DisplayName("media-service: 画像生成ジョブ用Executor(issue #1405)")
class AsyncJobConfigTest {

    @Test
    void 画像生成用のExecutorは直列実行で待ち行列に上限がある() {
        Executor executor = new AsyncJobConfig().imageGenerationExecutor();

        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
        assertEquals(1, pool.getCorePoolSize());
        assertEquals(1, pool.getMaxPoolSize());
        assertEquals(10, pool.getQueueCapacity());
        assertTrue(pool.getThreadNamePrefix().startsWith("image-generation-"));
        pool.shutdown();
    }

    @Test
    void ハートビートを動かすためスケジューリングが有効になっている() {
        assertTrue(AsyncJobConfig.class.isAnnotationPresent(
                org.springframework.scheduling.annotation.EnableScheduling.class));
    }
}
