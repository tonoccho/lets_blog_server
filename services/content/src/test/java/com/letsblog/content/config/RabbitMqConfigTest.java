package com.letsblog.content.config;

import com.letsblog.common.messaging.CorrelationIdListenerAdvice;
import com.rabbitmq.client.Channel;
import org.aopalliance.aop.Advice;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
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
import static org.mockito.Mockito.mock;

/**
 * issue #1227: {@code eventsListenerContainerFactory}が{@code configurer.configure(...)}の
 * 設定したリトライadvice chainを丸ごと上書きせず、相関ID adviceと共存させることを、
 * 実ブローカー無しで検証する(log-writerの{@code RabbitMqConfigTest}、issue #1059と同じ方針
 * ――Beanメソッド・{@link ProxyFactory}経由でのAOP合成を直接検証する)。
 */
class RabbitMqConfigTest {

    private final RabbitMqConfig config = new RabbitMqConfig();

    /**
     * issue #1227: prependCorrelationAdviceが既存chainの先頭に相関ID adviceを追加する
     * (置き換えないこと)を、advice chain合成のロジック単体で検証する
     * (log-writerのRabbitMqConfigTest、issue #1059と同じ観点)。
     */
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
     * issue #1227 AC1: eventsListenerContainerFactoryが組み立てるadvice chainに、相関ID adviceと
     * configure()が設定したリトライadviceの両方が共存していることを確認する
     * (置き換えではなく合成されていること)。
     */
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
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);

        RabbitListenerContainerFactoryResult result = buildEventsFactory(configurer, connectionFactory);

        assertEquals(2, result.adviceChain.length,
                "相関ID adviceとリトライadviceの両方が残っているはず(置き換えられていない)");
        assertInstanceOf(CorrelationIdListenerAdvice.class, result.adviceChain[0],
                "相関ID adviceが先頭に来ているはず(MDC設定を先に行ってからリトライ判定に入る)");
    }

    /**
     * issue #1227 AC3: EventMessageListenerに相当するリスナーが有限回失敗した後、
     * requeueされない例外(=DLXへdead-letterされる状態)になり、その間相関IDがMDCに
     * 載っていたことを確認する(log-writerのRabbitMqConfigTestと同じ検証方針)。
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
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);

        RabbitListenerContainerFactoryResult result = buildEventsFactory(configurer, connectionFactory);

        AlwaysFailingListener target = new AlwaysFailingListener();

        // retryのRetryTemplateは失敗するたびinvocation.invocableClone().proceed()で再実行するため、
        // 実際のターゲットオブジェクトが必要(実オブジェクトが無いと2回目以降の再実行が
        // リフレクションでnullターゲットを叩いてNPEになる)。
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setInterfaces(FakeListener.class);
        for (Advice advice : result.adviceChain) {
            proxyFactory.addAdvice(advice);
        }
        FakeListener proxy = (FakeListener) proxyFactory.getProxy();

        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setHeader(CORRELATION_ID_HEADER, "correlation-1227");
        Message message = new Message(new byte[0], messageProperties);

        Throwable thrown = assertThrows(Throwable.class, () -> proxy.onMessage(null, message));
        assertNotNull(findCause(thrown, AmqpRejectAndDontRequeueException.class),
                "最終的にAmqpRejectAndDontRequeueExceptionへ辿り着くはず。実際: " + thrown);

        // maxRetries(3)は「初回失敗後のリトライ回数」であり、初回込みの総試行回数は4回になる。
        assertEquals(4, target.invocationCount.get());
        assertEquals(4, target.observedCorrelationIdsInMdc.size());
        for (String observed : target.observedCorrelationIdsInMdc) {
            assertEquals("correlation-1227", observed);
        }
        assertNull(MDC.get(MDC_KEY), "アドバイスのfinallyでMDCから除去されているはず");
    }

    /**
     * {@code RabbitMqConfig#eventsListenerContainerFactory}と同じ手順を、テストから直接呼べる
     * 形で再現する(Beanメソッド自体はSpring注入前提の引数を取るため、ここではconfigurer/
     * connectionFactoryだけを渡して同じ組み立て手順を通す)。
     */
    private RabbitListenerContainerFactoryResult buildEventsFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(new JacksonJsonMessageConverter());
        factory.setAdviceChain(config.prependCorrelationAdvice(factory.getAdviceChain()));
        return new RabbitListenerContainerFactoryResult(factory.getAdviceChain());
    }

    private record RabbitListenerContainerFactoryResult(Advice[] adviceChain) {
    }

    /**
     * {@code StatelessRetryOperationsInterceptorFactoryBean.recover}が{@code args[1]}を
     * メッセージとして取り出す(RabbitMQの実際のリスナー呼び出し規約{@code (Channel, Message)}に
     * 合わせた実装のため)、引数順を{@code (Channel, Message)}にする。
     */
    interface FakeListener {
        void onMessage(Channel channel, Message message);
    }

    /** EventMessageListenerが業務例外を伝播させるのを模した、常に失敗するリスナー実装。 */
    static class AlwaysFailingListener implements FakeListener {
        private final AtomicInteger invocationCount = new AtomicInteger();
        private final List<String> observedCorrelationIdsInMdc = new java.util.ArrayList<>();

        @Override
        public void onMessage(Channel channel, Message message) {
            invocationCount.incrementAndGet();
            observedCorrelationIdsInMdc.add(MDC.get(MDC_KEY));
            throw new IllegalStateException("業務例外(冪等性チェック外での失敗等)を模した永続的な失敗");
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
