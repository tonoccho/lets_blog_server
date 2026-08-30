package com.letsblog.platform.config;

import com.letsblog.common.messaging.CorrelationIdMessagePostProcessor;
import com.letsblog.common.messaging.LogExchanges;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 監査ログメッセージキューイング(issue #466)用のRabbitMQ設定。platform-serviceはプロデューサー側
 * のみを担い(SystemSettingService/AppSettingServiceの@AuditLog、issue #693)、キュー/バインディング/
 * DLQの宣言はコンシューマー側(log-writer)が行う(exchangeの型/durableはコンシューマー側の宣言と
 * 一致させる必要がある)。system_settingsはproject/site/post等のドメインイベント(letsblog.events)には
 * 関与しないため、media-service/content-serviceと異なりeventsExchangeの購読は持たない
 * (media-serviceのRabbitMqConfig、issue #573 stage3と同じプロデューサー専用パターン)。
 */
@Configuration
public class RabbitMqConfig {

    @Bean
    public TopicExchange logsExchange() {
        return new TopicExchange(LogExchanges.LOG_EXCHANGE, true, false);
    }

    @Bean
    public JacksonJsonMessageConverter jacksonJsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, JacksonJsonMessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        // 発行元スレッドのMDCにある相関ID(issue #582)をメッセージヘッダへ付与する。
        template.setBeforePublishPostProcessors(new CorrelationIdMessagePostProcessor());
        return template;
    }
}
