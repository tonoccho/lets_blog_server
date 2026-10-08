package com.letsblog.publishing.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一括管理の環境間比較(TermComparisonService#resolveTermsByEnvironment)で、環境ごとの
 * agent経由取得を並列に行うための共有Executor(issue #1474)。
 *
 * <p>issue #1687から、書き込み系(BulkManagementService#applyToAllEnvironments/#executeFromUpload、
 * PluginThemeComparisonServiceの削除・反映と一覧取得、PostComparisonService#deleteEverywhere)の環境間並列にも
 * 同じExecutorを使う。WordPressへの同時要求の総量を1つの上限で抑えるため専用プールは作らない。
 * これらのタスクは別のタスクをこのプールへ投入して待つことはないので、プールの枯渇によるデッドロックはない。
 *
 * 並列度の上限は{@value #MAX_PARALLELISM}。根拠: 1リクエストが並列にする対象は環境数(local/test/
 * production)の最大3。プールをリクエストをまたいで共有するため、同時に2リクエスト分(3×2)までを
 * 外部への同時取得数の総上限とし、それ以上は環境数に関わらずキューで待たせる。環境ごとに別のWordPress
 * ホストなので、同一ホストへ掛かる同時要求は通常1本である。
 */
@Configuration
public class EnvironmentFetchExecutorConfig {

    public static final int MAX_PARALLELISM = 6;

    @Bean(destroyMethod = "shutdown")
    public ExecutorService environmentFetchExecutor() {
        return newExecutor();
    }

    /** Spring外(単体テスト等)でも同じ上限のExecutorを得るためのファクトリ。スレッドはdaemonなのでJVM終了を妨げない。 */
    public static ExecutorService newExecutor() {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory threads = runnable -> {
            Thread thread = new Thread(runnable, "environment-fetch-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(MAX_PARALLELISM, threads);
    }
}
