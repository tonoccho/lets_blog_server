package com.letsblog.ai.config;

import com.letsblog.common.async.MdcPropagatingTaskDecorator;
import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 数分かかりうる処理をHTTPリクエストをブロックせずに実行するための専用スレッドプール(issue #1675)。
 * デフォルトのSimpleAsyncTaskExecutorは無制限にスレッドを作るため、明示的なBeanで上限を設ける。
 */
@Configuration
@EnableAsync
public class AsyncJobConfig {

    /**
     * Ollamaのモデルのpull({@link com.letsblog.ai.service.OllamaPullJobRunner})。ホストのディスクと
     * 回線を使う重い処理なので並列度は2に抑える。待ち行列は大きく取り、溢れて受け付けられずに
     * ジョブがrunningのまま残ることを実質無くす(それでも残った場合は
     * {@link com.letsblog.ai.service.StaleGenerationJobSweepService}がfailedにする)。
     */
    @Bean(name = "ollamaPullExecutor")
    public Executor ollamaPullExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ollama-pull-");
        // 起動したリクエストの処理ID(MDC)をジョブの実行スレッドへ引き継ぐ(issue #1731)。
        executor.setTaskDecorator(new MdcPropagatingTaskDecorator());
        executor.initialize();
        return executor;
    }
}
