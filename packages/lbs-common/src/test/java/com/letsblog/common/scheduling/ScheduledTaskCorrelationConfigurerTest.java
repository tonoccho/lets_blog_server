package com.letsblog.common.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.letsblog.common.web.CorrelationIdFilter;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/** 各サービスが登録するSchedulingConfigurerの単体テスト(issue #1733)。 */
class ScheduledTaskCorrelationConfigurerTest {

    @Test
    void registrarに処理ID採番のスケジューラを設定する() {
        ScheduledTaskCorrelationConfigurer configurer = new ScheduledTaskCorrelationConfigurer();
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        configurer.configureTasks(registrar);

        assertInstanceOf(ScheduledTaskCorrelationScheduler.class, registrar.getScheduler());
        configurer.destroy();
    }

    @Test
    void 実際にタイマーから起動された処理が処理IDを持つ() throws Exception {
        ScheduledTaskCorrelationConfigurer configurer = new ScheduledTaskCorrelationConfigurer();
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        configurer.configureTasks(registrar);
        AtomicReference<String> id = new AtomicReference<>();
        CountDownLatch ran = new CountDownLatch(1);
        registrar.addFixedDelayTask(() -> {
            id.set(MDC.get(CorrelationIdFilter.MDC_KEY));
            ran.countDown();
        }, Duration.ofMillis(10));

        registrar.afterPropertiesSet();
        try {
            assertTrue(ran.await(5, TimeUnit.SECONDS));
            assertNotNull(id.get());
            assertEquals(36, id.get().length());
        } finally {
            registrar.destroy();
            configurer.destroy();
        }
    }
}
