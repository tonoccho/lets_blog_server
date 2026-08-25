package com.letsblog.api.config;

import com.letsblog.common.messaging.EventExchanges;
import com.letsblog.common.messaging.LogExchanges;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ログメッセージキューイング(issue #466)・ドメインイベント配信(issue #580)用のRabbitMQ設定。
 * apiはいずれもプロデューサー側のみを担い、キュー/バインディング/DLQの宣言はコンシューマーである
 * log-writer/各ドメインサービス側が行う(exchangeの型/durableはコンシューマー側の宣言と一致させる
 * 必要がある)。exchange名/routing keyはlbs-common({@link LogExchanges}/{@link EventExchanges})で
 * サービス間共有する。
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
        return template;
    }
}
