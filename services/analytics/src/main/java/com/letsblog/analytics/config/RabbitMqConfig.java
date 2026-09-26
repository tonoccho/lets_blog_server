package com.letsblog.analytics.config;

import com.letsblog.common.messaging.CorrelationIdListenerAdvice;
import com.letsblog.common.messaging.EventExchanges;
import org.aopalliance.aop.Advice;
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
 * letsblog.events(issue #580)用のRabbitMQ設定。analytics-serviceはproject.deletedのみを購読する
 * (analytics_credentialsのクリーンアップ)。DLQ/リトライ方針はcontent-serviceのRabbitMqConfigと
 * 同じ(docs/EVENT_DRIVEN_ARCHITECTURE.md参照)。
 */
@Configuration
public class RabbitMqConfig {

    public static final String PROJECT_DELETED_QUEUE = "analytics.project-deleted.queue";

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
        // configure()が設定したリトライ→DLQのadvice chain(issue #580)を丸ごと置き換えず、
        // 相関ID(issue #582)のMDC設定を先頭に足す(消さずに共存させる、issue #1276)。
        factory.setAdviceChain(prependCorrelationAdvice(factory.getAdviceChain()));
        return factory;
    }

    /**
     * 既存のadvice chainの先頭に{@link CorrelationIdListenerAdvice}を追加する(issue #1276、
     * content-serviceの{@code RabbitMqConfig#prependCorrelationAdvice}と同じ方針)。
     * 相関IDのMDC設定を先に行ってからリトライの成否判定に入るようにする(順序が重要)。
     */
    Advice[] prependCorrelationAdvice(Advice[] existing) {
        int existingLength = existing != null ? existing.length : 0;
        Advice[] combined = new Advice[existingLength + 1];
        combined[0] = new CorrelationIdListenerAdvice();
        if (existingLength > 0) {
            System.arraycopy(existing, 0, combined, 1, existingLength);
        }
        return combined;
    }
}
