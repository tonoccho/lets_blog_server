package com.letsblog.ai.config;

import com.letsblog.common.messaging.EventExchanges;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.amqp.support.converter.JacksonJavaTypeMapper;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * letsblog.events(issue #580)用のRabbitMQ設定。ai-serviceはproject.deletedのみを購読する
 * (project_ai_settingsのクリーンアップ)。DLQ/リトライ方針はcontent-serviceのRabbitMqConfigと
 * 同じ(docs/EVENT_DRIVEN_ARCHITECTURE.md参照)。
 */
@Configuration
public class RabbitMqConfig {

    public static final String PROJECT_DELETED_QUEUE = "ai.project-deleted.queue";

    @Bean
    public TopicExchange eventsExchange() {
        return new TopicExchange(EventExchanges.EVENTS_EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange eventsDlx() {
        return new TopicExchange(EventExchanges.EVENTS_DLX, true, false);
    }

    @Bean
    public Queue projectDeletedQueue() {
        return QueueBuilder.durable(PROJECT_DELETED_QUEUE)
                .withArgument("x-dead-letter-exchange", EventExchanges.EVENTS_DLX)
                .withArgument("x-dead-letter-routing-key", PROJECT_DELETED_QUEUE + EventExchanges.DLQ_SUFFIX)
                .build();
    }

    @Bean
    public Binding projectDeletedBinding(Queue projectDeletedQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(projectDeletedQueue).to(eventsExchange)
                .with(EventExchanges.PROJECT_DELETED_ROUTING_KEY);
    }

    @Bean
    public Queue projectDeletedDlq() {
        return QueueBuilder.durable(PROJECT_DELETED_QUEUE + EventExchanges.DLQ_SUFFIX).build();
    }

    @Bean
    public Binding projectDeletedDlqBinding(Queue projectDeletedDlq, TopicExchange eventsDlx) {
        return BindingBuilder.bind(projectDeletedDlq).to(eventsDlx)
                .with(PROJECT_DELETED_QUEUE + EventExchanges.DLQ_SUFFIX);
    }

    @Bean
    public JacksonJsonMessageConverter jacksonJsonMessageConverter() {
        JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter();
        converter.setTypePrecedence(JacksonJavaTypeMapper.TypePrecedence.INFERRED);
        return converter;
    }

    @Bean
    public RabbitListenerContainerFactory<?> eventsListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            JacksonJsonMessageConverter converter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(converter);
        return factory;
    }
}
