package com.letsblog.logwriter.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJavaTypeMapper;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ログメッセージキューイング(issue #466)用のRabbitMQ設定。log-writerはコンシューマー側として
 * exchange/キュー/バインディングの宣言を所有する(apiサーバーはexchangeのみを宣言してpublishする)。
 * exchange名/型/durableはapi側のRabbitMqConfigと一致させる必要がある。
 */
@Configuration
public class RabbitMqConfig {

    public static final String LOG_EXCHANGE = "letsblog.logs";
    public static final String ERROR_LOG_QUEUE = "error-logs.queue";
    public static final String OPERATION_LOG_QUEUE = "operation-logs.queue";
    public static final String AUDIT_LOG_QUEUE = "audit-logs.queue";
    public static final String ERROR_LOG_ROUTING_KEY = "log.error";
    public static final String OPERATION_LOG_ROUTING_KEY = "log.operation";
    public static final String AUDIT_LOG_ROUTING_KEY = "log.audit";

    @Bean
    public TopicExchange logsExchange() {
        return new TopicExchange(LOG_EXCHANGE, true, false);
    }

    @Bean
    public Queue errorLogQueue() {
        return new Queue(ERROR_LOG_QUEUE, true);
    }

    @Bean
    public Queue operationLogQueue() {
        return new Queue(OPERATION_LOG_QUEUE, true);
    }

    @Bean
    public Queue auditLogQueue() {
        return new Queue(AUDIT_LOG_QUEUE, true);
    }

    @Bean
    public Binding errorLogBinding(Queue errorLogQueue, TopicExchange logsExchange) {
        return BindingBuilder.bind(errorLogQueue).to(logsExchange).with(ERROR_LOG_ROUTING_KEY);
    }

    @Bean
    public Binding operationLogBinding(Queue operationLogQueue, TopicExchange logsExchange) {
        return BindingBuilder.bind(operationLogQueue).to(logsExchange).with(OPERATION_LOG_ROUTING_KEY);
    }

    @Bean
    public Binding auditLogBinding(Queue auditLogQueue, TopicExchange logsExchange) {
        return BindingBuilder.bind(auditLogQueue).to(logsExchange).with(AUDIT_LOG_ROUTING_KEY);
    }

    /**
     * apiサーバー(プロデューサー)側のメッセージ型はcom.letsblog.api.messagingパッケージ、
     * log-writer(コンシューマー)側はcom.letsblog.logwriter.messagingパッケージと、
     * 別モジュールゆえにクラスが異なるため、送信側が付与する__TypeId__ヘッダー(クラス名)による
     * 型解決は使わず、常に@RabbitListenerメソッドの引数型から推論させる。
     */
    @Bean
    public JacksonJsonMessageConverter jacksonJsonMessageConverter() {
        JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter();
        converter.setTypePrecedence(JacksonJavaTypeMapper.TypePrecedence.INFERRED);
        return converter;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory, JacksonJsonMessageConverter converter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(converter);
        return factory;
    }
}
