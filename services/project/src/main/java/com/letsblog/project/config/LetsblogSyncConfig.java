package com.letsblog.project.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * letsblog プラグインへの同期(issue #1558)を、保存の応答と切り離して実行するスレッド。
 * 1本にして順に実行する: 同じサイトへの同期が重ならず、最後の依頼が最後に反映される
 * (各実行が実行時点の最新の内容を読むため、古い内容が新しい内容を上書きしない)。
 */
@Configuration
public class LetsblogSyncConfig {

    @Bean(name = "letsblogSyncExecutor", destroyMethod = "shutdown")
    public java.util.concurrent.ExecutorService letsblogSyncExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "letsblog-sync");
            thread.setDaemon(true);
            return thread;
        });
    }
}
