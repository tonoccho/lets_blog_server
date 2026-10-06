package com.letsblog.project.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** issue #1479: サイト自動構築ジョブ用の小さな専用Executor。 */
@DisplayName("project-service: AsyncJobConfig(issue #1479)")
class AsyncJobConfigTest {

    @Test
    @DisplayName("@Async を有効にしている")
    void asyncEnabled() {
        assertNotNull(AsyncJobConfig.class.getAnnotation(EnableAsync.class));
    }

    @Test
    @DisplayName("siteProvisioningExecutor は共有コンテナ1台を相手にするため core 1 / max 2 / 待ち行列 5 の小さい設定")
    void siteProvisioningExecutorIsSmall() {
        Executor executor = new AsyncJobConfig().siteProvisioningExecutor();

        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
        assertEquals(1, pool.getCorePoolSize());
        assertEquals(2, pool.getMaxPoolSize());
        ThreadPoolExecutor underlying = pool.getThreadPoolExecutor();
        assertEquals(5, underlying.getQueue().remainingCapacity());
        assertTrue(pool.getThreadNamePrefix().startsWith("site-provisioning"));
        pool.shutdown();
    }

    @Test
    @DisplayName("Bean 名は ManagedSiteProvisioningJobRunner の @Async が指す名前と一致する")
    void beanNameMatchesAsyncQualifier() throws Exception {
        String beanName = AsyncJobConfig.class.getMethod("siteProvisioningExecutor")
                .getAnnotation(org.springframework.context.annotation.Bean.class).name()[0];
        String qualifier = com.letsblog.project.service.ManagedSiteProvisioningJobRunner.class
                .getMethod("run", Long.class,
                        com.letsblog.project.dto.CreateManagedWordPressSiteRequest.class,
                        com.letsblog.project.service.ActorSnapshot.class)
                .getAnnotation(org.springframework.scheduling.annotation.Async.class).value();
        assertEquals(beanName, qualifier);
    }

    @Test
    @DisplayName("textGenerationExecutor は LLM 待ちが主なので core 2 / max 4 / 待ち行列 20(issue #1409)")
    void textGenerationExecutorIsSmall() {
        Executor executor = new AsyncJobConfig().textGenerationExecutor();

        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
        assertEquals(2, pool.getCorePoolSize());
        assertEquals(4, pool.getMaxPoolSize());
        assertEquals(20, pool.getThreadPoolExecutor().getQueue().remainingCapacity());
        assertTrue(pool.getThreadNamePrefix().startsWith("text-generation"));
        pool.shutdown();
    }

    @Test
    @DisplayName("Bean 名は TextGenerationJobRunner の2つの @Async が指す名前と一致する(issue #1409)")
    void textGenerationBeanNameMatchesAsyncQualifier() throws Exception {
        String beanName = AsyncJobConfig.class.getMethod("textGenerationExecutor")
                .getAnnotation(org.springframework.context.annotation.Bean.class).name()[0];
        Class<?> runner = com.letsblog.project.service.TextGenerationJobRunner.class;
        String staticQualifier = runner
                .getMethod("runStaticContent", Long.class, Long.class,
                        com.letsblog.project.domain.StaticContentType.class, String.class)
                .getAnnotation(org.springframework.scheduling.annotation.Async.class).value();
        String designQualifier = runner
                .getMethod("runTagDesign", Long.class, Long.class,
                        com.letsblog.project.domain.EmbedTagType.class, String.class)
                .getAnnotation(org.springframework.scheduling.annotation.Async.class).value();
        assertEquals(beanName, staticQualifier);
        assertEquals(beanName, designQualifier);
    }
}
