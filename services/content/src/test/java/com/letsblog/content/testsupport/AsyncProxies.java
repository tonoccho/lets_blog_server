package com.letsblog.content.testsupport;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.scheduling.annotation.AsyncAnnotationAdvisor;

import java.util.concurrent.Executor;

/**
 * {@code @Async}を、Springの実際のAOPプロキシ({@link AsyncAnnotationAdvisor})で効かせるための部品(issue #1821)。
 * 自前のクラス(ジョブのランナー)はモックせず実物をそのまま包み、実行先のExecutorだけを渡す。
 */
public final class AsyncProxies {

    private AsyncProxies() {
    }

    /**
     * {@code target}の{@code @Async("<executorBeanName>")}メソッドを、{@code executor}で非同期に実行するプロキシを返す。
     * Executorが受け付けられない(満杯)ときは、呼び出しが{@code TaskRejectedException}になる。
     */
    @SuppressWarnings("unchecked")
    public static <T> T async(T target, String executorBeanName, Executor executor) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton(executorBeanName, executor);
        AsyncAnnotationAdvisor advisor = new AsyncAnnotationAdvisor(() -> executor, null);
        advisor.setBeanFactory(beans);
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvisor(advisor);
        return (T) factory.getProxy();
    }

    /**
     * 実行枠も待ち行列も満杯のExecutor(core 1 / max 1 / queue 0 で、唯一のスレッドが解放されるまで塞がっている)を返す。
     * このExecutorへ渡した{@code @Async}の呼び出しは{@code TaskRejectedException}になる。{@code release}で塞ぎを解く。
     */
    public static org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor saturated(
            java.util.concurrent.CountDownLatch release) {
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.initialize();
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        executor.execute(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            started.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return executor;
    }
}
