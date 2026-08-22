package com.letsblog.api.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ログメッセージキューイング(issue #466)用のRabbitMQ設定。apiはプロデューサー側のみを担い、
 * キュー/バインディングの宣言はコンシューマーであるlog-writerサービス側が行う
 * (exchangeの型/durableはlog-writer側の宣言と一致させる必要がある)。
 */
@Configuration
public class RabbitMqConfig {

    public static final String LOG_EXCHANGE = "letsblog.logs";
    public static final String ERROR_LOG_ROUTING_KEY = "log.error";
    public static final String OPERATION_LOG_ROUTING_KEY = "log.operation";
    public static final String AUDIT_LOG_ROUTING_KEY = "log.audit";

    @Bean
    public TopicExchange logsExchange() {
        return new TopicExchange(LOG_EXCHANGE, true, false);
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
