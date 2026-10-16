package com.letsblog.publishing.testsupport;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.scheduling.annotation.AsyncAnnotationAdvisor;

import java.util.concurrent.Executor;

/**
 * {@code @Async}を、Springの実際のAOPプロキシ({@link AsyncAnnotationAdvisor})で効かせるための部品(issue #1815)。
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
}
