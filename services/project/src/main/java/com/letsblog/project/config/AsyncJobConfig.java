package com.letsblog.project.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 数分かかる処理をHTTPリクエストをブロックせずに実行するための専用スレッドプール(issue #1479)。 */
@Configuration
@EnableAsync
public class AsyncJobConfig {

    /**
     * サイト自動構築ジョブ。provision-agentは共有の{@code lbs-wordpress}コンテナ1台に対してwp-cliを叩くので、
     * 並列度を上げても速くならず、同時構築は衝突しうる。media-serviceの{@code mediaGarbageCollectionExecutor}
     * (core 1 / max 2)に近い小さな値にし、待ち行列は5件。溢れた要求は受理側がジョブを
     * {@code queue_full}のfailedにして返す。同期API({@code POST /api/sites/managed-wordpress})は使わない。
     */
    @Bean(name = "siteProvisioningExecutor")
    public Executor siteProvisioningExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(5);
        executor.setThreadNamePrefix("site-provisioning-");
        executor.initialize();
        return executor;
    }

    /**
     * 静的コンテンツ・タグデザインのAI生成ジョブ(issue #1409)。実行時間の大半はai-serviceのLLM応答待ちで、
     * 共有コンテナのような単一資源を取り合わないので、並列度は小さく(core 2 / max 4)抑え、待ち行列は20件。
     * 溢れた要求は受理側がジョブを{@code queue_full}のfailedにして返す。同期API
     * ({@code POST .../generate})はこのExecutorを使わない。
     */
    @Bean(name = "textGenerationExecutor")
    public Executor textGenerationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("text-generation-");
        executor.initialize();
        return executor;
    }
}
