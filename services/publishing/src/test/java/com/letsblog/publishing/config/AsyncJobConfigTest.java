package com.letsblog.publishing.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** issue #1702: 全環境インストールジョブ用の小さな専用Executor(project-serviceの{@code AsyncJobConfig}と同じ形)。 */
@DisplayName("publishing-service: AsyncJobConfig(issue #1702)")
class AsyncJobConfigTest {

    @Test
    @DisplayName("@Async を有効にしている")
    void asyncEnabled() {
        assertNotNull(AsyncJobConfig.class.getAnnotation(EnableAsync.class));
    }

    @Test
    @DisplayName("bulkInstallExecutor は共有コンテナへの書き込みなので core 1 / max 2 / 待ち行列 5 の小さい設定")
    void bulkInstallExecutorIsSmall() {
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) new AsyncJobConfig().bulkInstallExecutor();

        assertEquals(1, pool.getCorePoolSize());
        assertEquals(2, pool.getMaxPoolSize());
        assertEquals(5, pool.getThreadPoolExecutor().getQueue().remainingCapacity());
        assertTrue(pool.getThreadNamePrefix().startsWith("bulk-install"));
        pool.shutdown();
    }

    @Test
    @DisplayName("Bean 名は BulkInstallJobRunner の2つの @Async が指す名前と一致する")
    void beanNameMatchesAsyncQualifier() throws Exception {
        String beanName = AsyncJobConfig.class.getMethod("bulkInstallExecutor")
                .getAnnotation(org.springframework.context.annotation.Bean.class).name()[0];
        Class<?> runner = com.letsblog.publishing.service.BulkInstallJobRunner.class;
        String slug = runner.getMethod("runSlugInstall", Long.class, Long.class,
                        com.letsblog.publishing.domain.BulkOperationType.class, String.class, Long.class)
                .getAnnotation(org.springframework.scheduling.annotation.Async.class).value();
        String zip = runner.getMethod("runZipInstall", Long.class, Long.class,
                        com.letsblog.publishing.domain.BulkOperationType.class,
                        com.letsblog.publishing.service.BulkUploadStorageService.StoredZip.class, Long.class)
                .getAnnotation(org.springframework.scheduling.annotation.Async.class).value();
        assertEquals(beanName, slug);
        assertEquals(beanName, zip);
    }
}
