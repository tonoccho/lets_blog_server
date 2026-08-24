package com.letsblog.media.config;

import com.letsblog.common.messaging.LogExchanges;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 監査ログメッセージキューイング(issue #466)用のRabbitMQ設定。media-serviceはプロデューサー側
 * のみを担い(メディアガベージコレクションの監査ログ発行、#573 stage3)、キュー/バインディングの
 * 宣言はコンシューマーであるlog-writerサービス側が行う(exchangeの型/durableはlog-writer側の
 * 宣言と一致させる必要がある)。legacy-apiのRabbitMqConfig(プロデューサー側)と同一の設定。
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
        return template;
    }
}
