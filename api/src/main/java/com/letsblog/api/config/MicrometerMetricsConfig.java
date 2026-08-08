package com.letsblog.api.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Slf4j
@Configuration
public class MicrometerMetricsConfig {

    private final MeterRegistry meterRegistry;

    /**
     * MeterRegistry生成自体がこのクラスの@Bean(metricsCommonTags)を要求するため、
     * 即時注入すると循環参照(BeanCurrentlyInCreationException)になる。@Lazyで遅延解決する。
     */
    public MicrometerMetricsConfig(@Lazy MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> registry.config()
                .commonTags(
                        "application", "lets-blog-api",
                        "environment", "${app.environment:development}"
                );
    }

    public Counter createApiRequestCounter(String endpoint) {
        return Counter.builder("api.request.count")
                .tag("endpoint", endpoint)
                .description("Total number of API requests")
                .register(meterRegistry);
    }

    public Counter createApiErrorCounter(String endpoint, String errorType) {
        return Counter.builder("api.request.error")
                .tag("endpoint", endpoint)
                .tag("error_type", errorType)
                .description("Total number of API errors")
                .register(meterRegistry);
    }

    public Timer createApiResponseTimer(String endpoint) {
        return Timer.builder("api.response.time")
                .tag("endpoint", endpoint)
                .description("API response time in milliseconds")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    public Counter createDatabaseQueryCounter() {
        return Counter.builder("database.query.count")
                .description("Total number of database queries")
                .register(meterRegistry);
    }

    public Timer createDatabaseQueryTimer() {
        return Timer.builder("database.query.time")
                .description("Database query execution time")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }
}
