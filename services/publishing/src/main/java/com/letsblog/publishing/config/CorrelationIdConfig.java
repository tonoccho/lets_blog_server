package com.letsblog.publishing.config;

import com.letsblog.common.scheduling.ScheduledTaskCorrelationConfigurer;
import com.letsblog.common.web.CorrelationIdFilter;
import com.letsblog.common.web.CorrelationIdRestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 相関ID伝播(issue #582)。lbs-commonの{@link CorrelationIdFilter}をservlet filterとして登録する。
 * content-service/platform-serviceのCorrelationIdConfigと同じ実装。
 */
@Configuration
public class CorrelationIdConfig {

    @Bean
    public CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }

    /** 注入されたRestClient.Builderから作るクライアントにも処理IDをX-Correlation-Idで載せる(issue #1730)。 */
    @Bean
    public CorrelationIdRestClientCustomizer correlationIdRestClientCustomizer() {
        return new CorrelationIdRestClientCustomizer();
    }

    /** @Scheduledの実行ごとに新しい処理IDを採番し、完了行を出す(issue #1733)。 */
    @Bean
    public ScheduledTaskCorrelationConfigurer scheduledTaskCorrelationConfigurer() {
        return new ScheduledTaskCorrelationConfigurer();
    }
}
