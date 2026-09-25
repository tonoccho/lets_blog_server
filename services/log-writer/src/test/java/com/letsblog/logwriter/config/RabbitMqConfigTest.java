package com.letsblog.logwriter.config;

import com.letsblog.common.messaging.CorrelationIdListenerAdvice;
import com.rabbitmq.client.Channel;
import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.letsblog.common.web.CorrelationIdFilter.CORRELATION_ID_HEADER;
import static com.letsblog.common.web.CorrelationIdFilter.MDC_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * issue #1059: RabbitMqConfigのDLQ配線・リトライ上限・advice chainの合成順序を、
 * 実ブローカー無しで検証する(このリポジトリにRabbitMQ用のTestcontainers/テストハーネスは
 * 存在しないため、Beanメソッド・{@link ProxyFactory}経由でのAOP合成を直接検証する方針)。
 */
class RabbitMqConfigTest {

    private final RabbitMqConfig config = new RabbitMqConfig();

    @Test
    void mainQueueは自分のDLXへdead_letterする引数を持つ() {
        Queue queue = config.mainQueue("error-logs.queue");

        assertTrue(queue.isDurable());
        assertEquals(RabbitMqConfig.LOG_DLX, queue.getArguments().get("x-dead-letter-exchange"));
        assertEquals("error-logs.queue.dlq", queue.getArguments().get("x-dead-letter-routing-key"));
    }

    @Test
    void 全キューとも同じdead_letter設定になっている() {
        List<Queue> queues = List.of(
                config.mainQueue(RabbitMqConfig.ERROR_LOG_QUEUE),
                config.mainQueue(RabbitMqConfig.OPERATION_LOG_QUEUE),
                config.mainQueue(RabbitMqConfig.AUDIT_LOG_QUEUE));

        for (Queue queue : queues) {
            assertEquals(RabbitMqConfig.LOG_DLX, queue.getArguments().get("x-dead-letter-exchange"));
            assertTrue(queue.isDurable());
        }
    }

    @Test
    void dlqはキュー名にdlq接尾辞を付けたdurableキューになる() {
        Queue dlq = config.dlq(RabbitMqConfig.AUDIT_LOG_QUEUE);

        assertEquals("audit-logs.queue.dlq", dlq.getName());
        assertTrue(dlq.isDurable());
    }

    @Test
    void dlqBindingはdlq名と同じルーティングキーでdlxへ結線する() {
        Queue dlq = config.dlq(RabbitMqConfig.OPERATION_LOG_QUEUE);
        TopicExchange dlx = new TopicExchange(RabbitMqConfig.LOG_DLX, true, false);

        var binding = config.dlqBinding(dlq, dlx, RabbitMqConfig.OPERATION_LOG_QUEUE);

        assertEquals("operation-logs.queue.dlq", binding.getRoutingKey());
        assertEquals(RabbitMqConfig.LOG_DLX, binding.getExchange());
    }

    @Test
    void 相関ID_adviceが既存のadvice_chainの先頭に追加される() {
        Advice retryAdvice = mock(Advice.class);

        Advice[] combined = config.prependCorrelationAdvice(new Advice[] {retryAdvice});

        assertEquals(2, combined.length);
        assertInstanceOf(CorrelationIdListenerAdvice.class, combined[0]);
        assertEquals(retryAdvice, combined[1]);
    }

    @Test
    void 既存chainが空でも相関ID_adviceだけのchainになる() {
        Advice[] combined = config.prependCorrelationAdvice(new Advice[0]);

        assertEquals(1, combined.length);
        assertInstanceOf(CorrelationIdListenerAdvice.class, combined[0]);
    }

    @Test
    void 既存chainがnullでも相関ID_adviceだけのchainになる() {
        Advice[] combined = config.prependCorrelationAdvice(null);

        assertEquals(1, combined.length);
        assertInstanceOf(CorrelationIdListenerAdvice.class, combined[0]);
    }

    /**
     * issue #1059 AC3/AC5: configurer.configure()が組み立てるリトライadvice + 相関ID adviceを
     * 実際にAOPプロキシへ適用し、(a)有限回で諦めて{@link AmqpRejectAndDontRequeueException}
     * になること(=requeueされずDLXへdead-letterされる状態になること)、(b)その間MDCへ
     * 相関IDが設定されていたことを確認する。ブローカーへの実接続は行わない。
     */
    @Test
    void リトライ上限を超えるとrequeueされない例外になり相関IDはmdcに載っている() throws Exception {
        RabbitProperties properties = new RabbitProperties();
        RabbitProperties.ListenerRetry retry = properties.getListener().getSimple().getRetry();
        retry.setEnabled(true);
        retry.setMaxRetries(3);
        retry.setInitialInterval(Duration.ofMillis(1));
        retry.setMultiplier(1.0);
        retry.setMaxInterval(Duration.ofMillis(1));

        SimpleRabbitListenerContainerFactoryConfigurer configurer =
                new SimpleRabbitListenerContainerFactoryConfigurer(properties);
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(new JacksonJsonMessageConverter());
        factory.setAdviceChain(config.prependCorrelationAdvice(factory.getAdviceChain()));

        assertEquals(2, factory.getAdviceChain().length);
        assertInstanceOf(CorrelationIdListenerAdvice.class, factory.getAdviceChain()[0]);

        AlwaysFailingListener target = new AlwaysFailingListener();

        // retryのRetryTemplateは失敗するたびinvocation.invocableClone().proceed()で再実行するため、
        // (単なるMethodInterceptorの終端ではなく)実際のターゲットオブジェクトが必要
        // (実オブジェクトが無いと2回目以降の再実行がリフレクションでnullターゲットを叩いてNPEになる)。
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setInterfaces(FakeListener.class);
        for (Advice advice : factory.getAdviceChain()) {
            proxyFactory.addAdvice(advice);
        }
        FakeListener proxy = (FakeListener) proxyFactory.getProxy();

        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setHeader(CORRELATION_ID_HEADER, "correlation-1059");
        Message message = new Message(new byte[0], messageProperties);

        Throwable thrown = assertThrows(Throwable.class, () -> proxy.onMessage(null, message));
        assertNotNull(findCause(thrown, AmqpRejectAndDontRequeueException.class),
                "最終的にAmqpRejectAndDontRequeueExceptionへ辿り着くはず。実際: " + thrown);

        // maxRetries(3)は「初回失敗後のリトライ回数」であり、初回込みの総試行回数は4回になる。
        assertEquals(4, target.invocationCount.get());
        assertEquals(4, target.observedCorrelationIdsInMdc.size());
        for (String observed : target.observedCorrelationIdsInMdc) {
            assertEquals("correlation-1059", observed);
        }
        assertNull(MDC.get(MDC_KEY), "アドバイスのfinallyでMDCから除去されているはず");
    }

    /**
     * {@code StatelessRetryOperationsInterceptorFactoryBean.recover}が{@code args[1]}を
     * メッセージとして取り出す(RabbitMQの実際のリスナー呼び出し規約{@code (Channel, Message)}に
     * 合わせた実装のため)、引数順を{@code (Channel, Message)}にする。
     */
    interface FakeListener {
        void onMessage(Channel channel, Message message);
    }

    /** {@code DataIntegrityViolationException}を模した、常に失敗するリスナー実装。 */
    static class AlwaysFailingListener implements FakeListener {
        private final AtomicInteger invocationCount = new AtomicInteger();
        private final List<String> observedCorrelationIdsInMdc = new java.util.ArrayList<>();

        @Override
        public void onMessage(Channel channel, Message message) {
            invocationCount.incrementAndGet();
            observedCorrelationIdsInMdc.add(MDC.get(MDC_KEY));
            throw new IllegalStateException("DataIntegrityViolationExceptionを模した永続的な失敗");
        }
    }

    private static Throwable findCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return current;
            }
            current = current.getCause();
        }
        return null;
    }
}
