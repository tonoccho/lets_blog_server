package com.letsblog.identity.config;

import com.letsblog.common.messaging.CorrelationIdListenerAdvice;
import com.letsblog.common.messaging.EventExchanges;
import org.aopalliance.aop.Advice;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/**
 * issue #1324: project.environment-boundを購読するキュー/バインディング/DLQと、
 * 相関ID(#1276)とリトライを共存させるリスナーコンテナファクトリの宣言を固定する。
 */
class RabbitMqConfigTest {

    private final RabbitMqConfig config = new RabbitMqConfig();

    @Test
    void キューはDLXへdead_letterされ_バインディングはproject_environment_boundのroutingKeyを使う() {
        Queue queue = config.projectEnvironmentBoundQueue();
        Binding binding = config.projectEnvironmentBoundBinding(queue, config.eventsExchange());

        assertEquals(RabbitMqConfig.PROJECT_ENVIRONMENT_BOUND_QUEUE, queue.getName());
        assertEquals(EventExchanges.EVENTS_DLX, queue.getArguments().get("x-dead-letter-exchange"));
        assertEquals(RabbitMqConfig.PROJECT_ENVIRONMENT_BOUND_QUEUE + EventExchanges.DLQ_SUFFIX,
                queue.getArguments().get("x-dead-letter-routing-key"));
        assertEquals(EventExchanges.PROJECT_ENVIRONMENT_BOUND_ROUTING_KEY, binding.getRoutingKey());
        assertEquals(EventExchanges.EVENTS_EXCHANGE, binding.getExchange());
    }

    @Test
    void DLQとそのバインディングはDLXに宣言される() {
        TopicExchange dlx = config.eventsDlx();
        Queue dlq = config.projectEnvironmentBoundDlq();
        Binding binding = config.projectEnvironmentBoundDlqBinding(dlq, dlx);

        assertEquals(EventExchanges.EVENTS_DLX, dlx.getName());
        assertEquals(RabbitMqConfig.PROJECT_ENVIRONMENT_BOUND_QUEUE + EventExchanges.DLQ_SUFFIX, dlq.getName());
        assertEquals(dlq.getName(), binding.getRoutingKey());
        assertEquals(EventExchanges.EVENTS_DLX, binding.getExchange());
    }

    @Test
    void prependCorrelationAdviceは既存chainの先頭に相関ID_adviceを足す() {
        Advice retry = mock(Advice.class);

        Advice[] combined = config.prependCorrelationAdvice(new Advice[] {retry});

        assertEquals(2, combined.length);
        assertInstanceOf(CorrelationIdListenerAdvice.class, combined[0]);
        assertSame(retry, combined[1]);
    }

    @Test
    void prependCorrelationAdviceは既存chainが空でもnullでも相関ID_adviceだけのchainにする() {
        assertEquals(1, config.prependCorrelationAdvice(new Advice[0]).length);
        Advice[] fromNull = config.prependCorrelationAdvice(null);
        assertEquals(1, fromNull.length);
        assertInstanceOf(CorrelationIdListenerAdvice.class, fromNull[0]);
    }

    @Test
    void eventsリスナーのadvice_chainに相関IDとリトライの両方が共存する() {
        RabbitProperties properties = new RabbitProperties();
        RabbitProperties.ListenerRetry retry = properties.getListener().getSimple().getRetry();
        retry.setEnabled(true);
        retry.setMaxRetries(3);
        retry.setInitialInterval(Duration.ofMillis(1));
        retry.setMultiplier(1.0);
        retry.setMaxInterval(Duration.ofMillis(1));
        SimpleRabbitListenerContainerFactoryConfigurer configurer =
                new SimpleRabbitListenerContainerFactoryConfigurer(properties);

        SimpleRabbitListenerContainerFactory factory = (SimpleRabbitListenerContainerFactory)
                config.eventsListenerContainerFactory(configurer, mock(ConnectionFactory.class),
                        config.jacksonJsonMessageConverter());

        Advice[] chain = factory.getAdviceChain();
        assertEquals(2, chain.length);
        assertInstanceOf(CorrelationIdListenerAdvice.class, chain[0]);
    }
}
