package com.letsblog.identity.config;

import com.letsblog.common.messaging.EventExchanges;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ドメインイベント配信(issue #580)用のRabbitMQ設定。identity-serviceはuser.deactivatedイベントの
 * プロデューサー側のみを担い、キュー/バインディング/DLQの宣言はコンシューマー側(content-service等)が
 * 行う(exchangeの型/durableはコンシューマー側の宣言と一致させる必要がある)。legacy-api/media-service
 * のRabbitMqConfig(プロデューサー側)と同一の設定。
 */
@Configuration
public class RabbitMqConfig {

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
        return template;
    }
}
