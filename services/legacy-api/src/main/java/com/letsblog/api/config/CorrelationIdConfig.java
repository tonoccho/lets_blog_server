package com.letsblog.api.config;

import com.letsblog.common.web.CorrelationIdFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 相関ID伝播(issue #582)。lbs-commonの{@link CorrelationIdFilter}をservlet filterとして登録する。
 * {@link CorrelationIdFilter}は{@code @Order(HIGHEST_PRECEDENCE)}のため、{@link HttpLoggingFilter}
 * より先に実行され、HttpLoggingFilterのログ出力時にはMDCへ相関IDが設定済みになる。
 */
@Configuration
public class CorrelationIdConfig {

    @Bean
    public CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }
}
