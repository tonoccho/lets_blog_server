package com.letsblog.logwriter.config;

import com.letsblog.common.messaging.CorrelationIdListenerAdvice;
import com.letsblog.common.messaging.LogExchanges;
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
 * exchange名/型/durableはapi側のRabbitMqConfigと一致させる必要がある
 * (exchange名/routing keyはlbs-common({@link LogExchanges})でサービス間共有する)。
 */
@Configuration
public class RabbitMqConfig {

    public static final String ERROR_LOG_QUEUE = "error-logs.queue";
    public static final String OPERATION_LOG_QUEUE = "operation-logs.queue";
    public static final String AUDIT_LOG_QUEUE = "audit-logs.queue";

    @Bean
    public TopicExchange logsExchange() {
        return new TopicExchange(LogExchanges.LOG_EXCHANGE, true, false);
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
        return BindingBuilder.bind(errorLogQueue).to(logsExchange).with(LogExchanges.ERROR_LOG_ROUTING_KEY);
    }

    @Bean
    public Binding operationLogBinding(Queue operationLogQueue, TopicExchange logsExchange) {
        return BindingBuilder.bind(operationLogQueue).to(logsExchange).with(LogExchanges.OPERATION_LOG_ROUTING_KEY);
    }

    @Bean
    public Binding auditLogBinding(Queue auditLogQueue, TopicExchange logsExchange) {
        return BindingBuilder.bind(auditLogQueue).to(logsExchange).with(LogExchanges.AUDIT_LOG_ROUTING_KEY);
    }

    /**
     * メッセージ型(lbs-commonの{@code com.letsblog.common.messaging}パッケージ)はプロデューサー・
     * コンシューマー双方で同じクラスを参照するようになったが、送信側が付与する__TypeId__ヘッダー
     * (クラス名)による型解決には依存せず、引き続き@RabbitListenerメソッドの引数型から推論させる
     * (プロデューサーが増えても解決方式を変えずに済むため)。
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
        // メッセージヘッダの相関ID(issue #582)をMDCへ設定してからリスナーメソッドを呼び出す。
        factory.setAdviceChain(new CorrelationIdListenerAdvice());
        return factory;
    }
}
