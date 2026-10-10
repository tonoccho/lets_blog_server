package com.letsblog.common.scheduling;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * {@code @EnableScheduling}を持つサービスが{@code @Bean}として登録し、{@code @Scheduled}の実行を
 * {@link ScheduledTaskCorrelationScheduler}経由にする(issue #1733)。{@code @EnableScheduling}を
 * 持つ全サービスが登録しているかは{@code ScheduledTaskCorrelationRegistrationContractTest}が検査する。
 *
 * <p>スレッド数は1(Spring Bootの既定{@code spring.task.scheduling.pool.size}と同じ)にして、
 * 登録前と同じ直列の実行モデルを保つ。
 */
public class ScheduledTaskCorrelationConfigurer implements SchedulingConfigurer, DisposableBean {

    private final ThreadPoolTaskScheduler threadPool = new ThreadPoolTaskScheduler();

    public ScheduledTaskCorrelationConfigurer() {
        threadPool.setPoolSize(1);
        threadPool.setThreadNamePrefix("scheduling-");
        threadPool.initialize();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setTaskScheduler(new ScheduledTaskCorrelationScheduler(threadPool));
    }

    @Override
    public void destroy() {
        threadPool.shutdown();
    }
}
