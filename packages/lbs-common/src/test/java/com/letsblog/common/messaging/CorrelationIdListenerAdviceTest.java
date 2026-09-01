package com.letsblog.common.messaging;

import com.letsblog.common.web.CorrelationIdFilter;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code SimpleRabbitListenerContainerFactory#setAdviceChain}経由でSpring AMQPリスナー
 * コンテナが呼び出す{@code ChannelAwareMessageListener#onMessage(Message, Channel)}invocationを
 * {@link FakeMethodInvocation}で模倣し、実際のRabbitMQ/コンテナ無しでMDCの設定・除去を検証する。
 */
class CorrelationIdListenerAdviceTest {

    private final CorrelationIdListenerAdvice advice = new CorrelationIdListenerAdvice();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void メッセージヘッダに相関IDがあればMDCへ設定し処理後に除去する() throws Throwable {
        MessageProperties properties = new MessageProperties();
        properties.setHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "test-correlation-id");
        Message message = new Message(new byte[0], properties);
        AtomicReference<String> mdcDuringInvocation = new AtomicReference<>();
        FakeMethodInvocation invocation = new FakeMethodInvocation(
                new Object[] {message}, () -> mdcDuringInvocation.set(MDC.get(CorrelationIdFilter.MDC_KEY)));

        advice.invoke(invocation);

        assertEquals("test-correlation-id", mdcDuringInvocation.get());
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY), "処理後はMDCから除去される");
    }

    @Test
    void メッセージヘッダに相関IDが無ければMDCを設定しない() throws Throwable {
        Message message = new Message(new byte[0], new MessageProperties());
        AtomicReference<String> mdcDuringInvocation = new AtomicReference<>();
        FakeMethodInvocation invocation = new FakeMethodInvocation(
                new Object[] {message}, () -> mdcDuringInvocation.set(MDC.get(CorrelationIdFilter.MDC_KEY)));

        advice.invoke(invocation);

        assertNull(mdcDuringInvocation.get());
    }

    /** {@link MethodInvocation}の必要最小限のテスト用実装({@code proceed()}のみ使う)。 */
    private static final class FakeMethodInvocation implements MethodInvocation {

        private final Object[] arguments;
        private final Runnable onProceed;

        private FakeMethodInvocation(Object[] arguments, Runnable onProceed) {
            this.arguments = arguments;
            this.onProceed = onProceed;
        }

        @Override
        public Object proceed() {
            onProceed.run();
            return null;
        }

        @Override
        public Object[] getArguments() {
            return arguments;
        }

        @Override
        public Method getMethod() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object getThis() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.lang.reflect.AccessibleObject getStaticPart() {
            throw new UnsupportedOperationException();
        }
    }
}
