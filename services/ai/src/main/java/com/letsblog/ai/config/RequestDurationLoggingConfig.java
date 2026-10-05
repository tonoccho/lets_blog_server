package com.letsblog.ai.config;

import com.letsblog.common.web.RequestDurationLoggingFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * リクエスト所要時間ログ(issue #1470)。lbs-commonの{@link RequestDurationLoggingFilter}を
 * servlet filterとして登録する。WARN閾値は{@code app.request-logging.slow-threshold-ms}
 * (既定{@value RequestDurationLoggingFilter#DEFAULT_SLOW_THRESHOLD_MS}ms)で変更できる。
 */
@Configuration
public class RequestDurationLoggingConfig {

    @Bean
    public RequestDurationLoggingFilter requestDurationLoggingFilter(
            @Value("${app.request-logging.slow-threshold-ms:"
                    + RequestDurationLoggingFilter.DEFAULT_SLOW_THRESHOLD_MS + "}") long slowThresholdMs) {
        return new RequestDurationLoggingFilter(slowThresholdMs);
    }
}
