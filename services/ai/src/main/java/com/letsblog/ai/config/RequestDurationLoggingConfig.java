package com.letsblog.ai.config;

import com.letsblog.common.db.DbQueryMetricsDataSourcePostProcessor;
import com.letsblog.common.db.DbQueryRecorder;
import com.letsblog.common.web.RequestDurationLoggingFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * リクエスト所要時間ログ(issue #1470)。lbs-commonの{@link RequestDurationLoggingFilter}を
 * servlet filterとして登録する。WARN閾値は{@code app.request-logging.slow-threshold-ms}
 * (既定{@value RequestDurationLoggingFilter#DEFAULT_SLOW_THRESHOLD_MS}ms)で変更できる。
 *
 * <p>DBクエリ記録(issue #1736): {@link DbQueryMetricsDataSourcePostProcessor}がDataSourceを包んで
 * リクエストごとの回数・時間を数え、{@code app.db-metrics.slow-query-threshold-ms}(単一クエリのWARN)と
 * {@code app.db-metrics.repeat-threshold}(同じSQLの繰り返しのWARN)を上記フィルタが使う。
 * 既定値と根拠は{@code docs/LOGGING_AND_MONITORING.md}。
 */
@Configuration
public class RequestDurationLoggingConfig {

    @Bean
    public RequestDurationLoggingFilter requestDurationLoggingFilter(
            @Value("${app.request-logging.slow-threshold-ms:"
                    + RequestDurationLoggingFilter.DEFAULT_SLOW_THRESHOLD_MS + "}") long slowThresholdMs,
            @Value("${app.db-metrics.slow-query-threshold-ms:"
                    + DbQueryRecorder.DEFAULT_SLOW_QUERY_THRESHOLD_MS + "}") long dbSlowQueryThresholdMs,
            @Value("${app.db-metrics.repeat-threshold:"
                    + DbQueryRecorder.DEFAULT_REPEAT_THRESHOLD + "}") int dbRepeatThreshold) {
        return new RequestDurationLoggingFilter(slowThresholdMs, dbSlowQueryThresholdMs, dbRepeatThreshold);
    }

    /** BeanPostProcessorは他のBeanより先に作られるため{@code static}にする。 */
    @Bean
    public static DbQueryMetricsDataSourcePostProcessor dbQueryMetricsDataSourcePostProcessor() {
        return new DbQueryMetricsDataSourcePostProcessor();
    }
}
