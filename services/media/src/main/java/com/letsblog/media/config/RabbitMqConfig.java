package com.letsblog.media.config;

import com.letsblog.common.messaging.CorrelationIdMessagePostProcessor;
import com.letsblog.common.messaging.EventExchanges;
import com.letsblog.common.messaging.LogExchanges;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 監査ログメッセージキューイング(issue #466)・ドメインイベント配信(issue #580)用のRabbitMQ設定。
 * media-serviceはいずれもプロデューサー側のみを担い(監査ログ発行(#573 stage3)、
 * image.generatedイベント発行(GeneratedImageController#create、issue #580))、キュー/バインディング/
 * DLQの宣言はコンシューマー側(log-writer/content-service)が行う(exchangeの型/durableは
 * コンシューマー側の宣言と一致させる必要がある)。legacy-apiのRabbitMqConfig(プロデューサー側)と
 * 同一の設定。
 */
@Configuration
public class RabbitMqConfig {

    @Bean
    public TopicExchange logsExchange() {
        return new TopicExchange(LogExchanges.LOG_EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange eventsExchange() {
        return new TopicExchange(EventExchanges.EVENTS_EXCHANGE, true, false);
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
