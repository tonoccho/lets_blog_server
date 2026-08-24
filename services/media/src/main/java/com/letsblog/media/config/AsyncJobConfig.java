package com.letsblog.media.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * ComfyUIチェックポイントダウンロード等、数分かかりうる処理をHTTPリクエストをブロックせずに
 * 実行するための専用スレッドプール。legacy-apiのAsyncJobConfig(#573でmedia-serviceへ移設)と
 * 同一の設定。デフォルトのSimpleAsyncTaskExecutorは無制限にスレッドを生成するため、
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

    /**
     * メディアガベージコレクションの一括削除(issue #500)。1サイトへの削除は逐次CMSブリッジ呼び出し
     * (legacy-api経由のSSH/エージェント接続)になるため並列度を上げても速くならず、小さいプールで
     * 十分(legacy-apiのAsyncJobConfigと同一設定、#573 stage3で移設)。
     */
    @Bean(name = "mediaGarbageCollectionExecutor")
    public Executor mediaGarbageCollectionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("media-gc-");
        executor.initialize();
        return executor;
    }
}
