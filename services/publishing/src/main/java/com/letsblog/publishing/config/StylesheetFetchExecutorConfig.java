package com.letsblog.publishing.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 記事プレビューのstylesheet取得(ArticlePreviewService#concatStylesheets)を並列に行うための
 * 共有Executor(issue #1473)。
 *
 * 並列度の上限は{@value #MAX_PARALLELISM}。根拠: ブラウザがホスト1つに対して同時に張る接続数の
 * 慣例値(HTTP/1.1で6本)に合わせている。プレビューは実際のブラウザの読み込みを代替するものなので、
 * ブラウザ以上の負荷を外部のWordPressサイトへ掛けない。プールはリクエストをまたいで共有するため、
 * 同時に複数のプレビューが走ってもこの値が外部への同時取得数の総上限になる(リクエストごとに
 * 上限を設けるだけでは、リクエスト数に比例して同時接続が増える)。
 * 上限を超えた分はキューで待つだけで、取得自体は行われる。
 */
@Configuration
public class StylesheetFetchExecutorConfig {

    public static final int MAX_PARALLELISM = 6;

    @Bean(destroyMethod = "shutdown")
    public ExecutorService stylesheetFetchExecutor() {
        return newExecutor();
    }

    /** Spring外(単体テスト等)でも同じ上限のExecutorを得るためのファクトリ。スレッドはdaemonなのでJVM終了を妨げない。 */
    public static ExecutorService newExecutor() {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory threads = runnable -> {
            Thread thread = new Thread(runnable, "stylesheet-fetch-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(MAX_PARALLELISM, threads);
    }
}
