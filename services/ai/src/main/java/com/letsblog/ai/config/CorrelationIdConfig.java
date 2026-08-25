package com.letsblog.ai.config;

import com.letsblog.common.web.CorrelationIdFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 相関ID伝播(issue #582)。lbs-commonの{@link CorrelationIdFilter}をservlet filterとして登録する。
 */
@Configuration
public class CorrelationIdConfig {

    @Bean
    public CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }
}
