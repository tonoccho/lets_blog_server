package com.letsblog.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * ComfyUIチェックポイントダウンロード等、数分かかりうる処理を
 * HTTPリクエストをブロックせずに実行するための専用スレッドプール。
 * デフォルトのSimpleAsyncTaskExecutorは無制限にスレッドを生成するため、
 * 同時ダウンロード数の上限を設けるために明示的なBeanを用意する。
 */
@Configuration
public class AsyncJobConfig {

    @Bean(name = "modelInstallExecutor")
    public Executor modelInstallExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("model-install-");
        executor.initialize();
        return executor;
    }

    /** Buffer APIへのSNS投稿予約(issue #379)。記事公開のHTTPレスポンスをブロックせずに実行する。 */
    @Bean(name = "bufferNotificationExecutor")
    public Executor bufferNotificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("buffer-notify-");
        executor.initialize();
        return executor;
    }
}
