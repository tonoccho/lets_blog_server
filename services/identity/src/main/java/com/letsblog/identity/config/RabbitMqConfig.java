package com.letsblog.identity.config;

import com.letsblog.common.messaging.CorrelationIdListenerAdvice;
import com.letsblog.common.messaging.CorrelationIdMessagePostProcessor;
import com.letsblog.common.messaging.EventExchanges;
import org.aopalliance.aop.Advice;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.amqp.support.converter.JacksonJavaTypeMapper;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ドメインイベント配信(issue #580)用のRabbitMQ設定。identity-serviceはuser.deactivatedイベントの
 * プロデューサーであり、これに加えてissue #1324でproject.environment-boundの購読側にもなった
 * (サイト紐付け前に追加したメンバーのWordPressユーザーの補填)。user.deactivatedのキュー/
 * バインディング/DLQの宣言はコンシューマー側(content-service等)が行う(exchangeの型/durableは
 * コンシューマー側の宣言と一致させる必要がある)。project.environment-boundは本サービスが読むため、
 * キュー・バインディング・DLQを本サービスが宣言する(「誰が読むか」が宣言責任を持つ)。
 * リスナー側の構成はanalytics-serviceのRabbitMqConfigと同じ。
 */
@Configuration
public class RabbitMqConfig {

    public static final String PROJECT_ENVIRONMENT_BOUND_QUEUE = "identity.project-environment-bound.queue";

    @Bean
    public TopicExchange eventsExchange() {
        return new TopicExchange(EventExchanges.EVENTS_EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange eventsDlx() {
        return new TopicExchange(EventExchanges.EVENTS_DLX, true, false);
    }

    @Bean
    public Queue projectEnvironmentBoundQueue() {
        return QueueBuilder.durable(PROJECT_ENVIRONMENT_BOUND_QUEUE)
                .withArgument("x-dead-letter-exchange", EventExchanges.EVENTS_DLX)
                .withArgument("x-dead-letter-routing-key",
                        PROJECT_ENVIRONMENT_BOUND_QUEUE + EventExchanges.DLQ_SUFFIX)
                .build();
    }

    @Bean
    public Binding projectEnvironmentBoundBinding(Queue projectEnvironmentBoundQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(projectEnvironmentBoundQueue).to(eventsExchange)
                .with(EventExchanges.PROJECT_ENVIRONMENT_BOUND_ROUTING_KEY);
    }

    @Bean
    public Queue projectEnvironmentBoundDlq() {
        return QueueBuilder.durable(PROJECT_ENVIRONMENT_BOUND_QUEUE + EventExchanges.DLQ_SUFFIX).build();
    }

    @Bean
    public Binding projectEnvironmentBoundDlqBinding(Queue projectEnvironmentBoundDlq, TopicExchange eventsDlx) {
        return BindingBuilder.bind(projectEnvironmentBoundDlq).to(eventsDlx)
                .with(PROJECT_ENVIRONMENT_BOUND_QUEUE + EventExchanges.DLQ_SUFFIX);
    }

    @Bean
    public JacksonJsonMessageConverter jacksonJsonMessageConverter() {
        JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter();
        // 受信時は、送信側のクラス名ヘッダではなくリスナー引数の型で復元する(analytics-serviceと同じ)。
        converter.setTypePrecedence(JacksonJavaTypeMapper.TypePrecedence.INFERRED);
        return converter;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, JacksonJsonMessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        // 発行元スレッドのMDCにある相関ID(issue #582)をメッセージヘッダへ付与する。
        template.setBeforePublishPostProcessors(new CorrelationIdMessagePostProcessor());
        return template;
    }

    @Bean
    public RabbitListenerContainerFactory<?> eventsListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            JacksonJsonMessageConverter converter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(converter);
        // configure()が設定したリトライ→DLQのadvice chainを置き換えず、相関ID(issue #1276)の
        // MDC設定を先頭に足す(analytics-serviceのRabbitMqConfigと同じ)。
        factory.setAdviceChain(prependCorrelationAdvice(factory.getAdviceChain()));
        return factory;
    }

    /** 既存のadvice chainの先頭に{@link CorrelationIdListenerAdvice}を追加する(順序が重要)。 */
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
