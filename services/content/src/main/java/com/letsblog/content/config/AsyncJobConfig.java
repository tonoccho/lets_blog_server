package com.letsblog.content.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 数十秒〜数分かかるLLM生成をHTTPリクエストをブロックせずに実行するための専用スレッドプール(issue #1409)。 */
@Configuration
@EnableAsync
public class AsyncJobConfig {

    /**
     * カスタムタグ生成ジョブ。実行時間の大半はai-serviceのLLM応答待ちで、GPUのような単一資源を
     * 取り合わないので、並列度は小さく(core 2 / max 4)抑え、待ち行列は20件。溢れた要求は受理側がジョブを
     * {@code queue_full}のfailedにして返す。同期API({@code POST /api/custom-tags/generate})は使わない。
     */
    @Bean(name = "customTagGenerationExecutor")
    public Executor customTagGenerationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("custom-tag-generation-");
        executor.initialize();
        return executor;
    }
}
