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
}
