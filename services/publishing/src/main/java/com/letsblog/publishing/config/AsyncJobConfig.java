package com.letsblog.publishing.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 数分かかりうる処理をHTTPリクエストをブロックせずに実行するための専用スレッドプール(issue #1702)。
 * project-serviceの{@code AsyncJobConfig}(#1479、#1697)と同じ形。
 */
@Configuration
@EnableAsync
public class AsyncJobConfig {

    /**
     * 全環境インストール(slug / zip)ジョブ(issue #1702)。各環境のインストールは共有の{@code lbs-wordpress}
     * コンテナへのwp-cli/ファイル転送で、並列度を上げても速くならない。1ジョブの中でも環境間の並列
     * ({@code EnvironmentFetchExecutorConfig}の共有Executor)があるので、ジョブ自体は小さい値
     * (core 1 / max 2 / 待ち行列5)にする。溢れた要求は受理側がジョブを{@code queue_full}のfailedにして返す。
     * 同期API({@code POST .../bulk-management/apply-all}・{@code .../upload})はこのExecutorを使わない。
     */
    @Bean(name = "bulkInstallExecutor")
    public Executor bulkInstallExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(5);
        executor.setThreadNamePrefix("bulk-install-");
        executor.initialize();
        return executor;
    }
}
