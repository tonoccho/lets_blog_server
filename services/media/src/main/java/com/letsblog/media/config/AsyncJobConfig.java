package com.letsblog.media.config;

import com.letsblog.common.async.MdcPropagatingTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * メディアガベージコレクション・画像生成・AIタグ付けなど、HTTPリクエストをブロックせずに実行する
 * 処理の専用スレッドプール。デフォルトのSimpleAsyncTaskExecutorは無制限にスレッドを生成するため、
 * 同時実行数の上限を設けるために明示的なBeanを用意する。ComfyUIチェックポイントのダウンロード用
 * プール(modelInstallExecutor)は、チェックポイントの導入機能ごと削除した(issue #1746)。
 */
@Configuration
@EnableScheduling
public class AsyncJobConfig {

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
        // 起動したリクエストの処理ID(MDC)をジョブの実行スレッドへ引き継ぐ(issue #1731)。
        executor.setTaskDecorator(new MdcPropagatingTaskDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * 画像生成ジョブ(issue #1405)。ComfyUIもホストGPUも1台なので並列度は1(直列)にする。
     * 溢れた要求は待ち行列(10件)で受け、それも満杯なら受理側がジョブをfailedにして返す。
     * 同期API({@code POST /api/ai/image})はこのExecutorを使わない。
     */
    @Bean(name = "imageGenerationExecutor")
    public Executor imageGenerationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("image-generation-");
        // 起動したリクエストの処理ID(MDC)をジョブの実行スレッドへ引き継ぐ(issue #1731)。
        executor.setTaskDecorator(new MdcPropagatingTaskDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * アップロード画像のAIタグ付け(issue #1600)。アップロード応答の後に非同期で行う補助処理で、
     * 1件はLLMの応答待ち(最大でai-serviceのタイムアウト)なので並列度は小さく抑える。
     * 溢れた要求は受理側がタグ付けを省略する(アップロード自体は成功させる)。
     */
    @Bean(name = "imageTaggingExecutor")
    public Executor imageTaggingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("image-tagging-");
        // 起動したリクエストの処理ID(MDC)をジョブの実行スレッドへ引き継ぐ(issue #1731)。
        executor.setTaskDecorator(new MdcPropagatingTaskDecorator());
        executor.initialize();
        return executor;
    }
}
