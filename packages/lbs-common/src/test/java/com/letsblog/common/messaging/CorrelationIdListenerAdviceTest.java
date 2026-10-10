package com.letsblog.common.messaging;

import com.letsblog.common.web.CorrelationIdFilter;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code SimpleRabbitListenerContainerFactory#setAdviceChain}経由でSpring AMQPリスナー
 * コンテナが呼び出す{@code ChannelAwareMessageListener#onMessage(Message, Channel)}invocationを
 * {@link FakeMethodInvocation}で模倣し、実際のRabbitMQ/コンテナ無しでMDCの設定・除去を検証する。
 */
class CorrelationIdListenerAdviceTest {

    private final CorrelationIdListenerAdvice advice = new CorrelationIdListenerAdvice();

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger adviceLogger = (Logger) LoggerFactory.getLogger(CorrelationIdListenerAdvice.class);
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        originalLevel = adviceLogger.getLevel();
        adviceLogger.setLevel(Level.DEBUG);
        appender.start();
        adviceLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        adviceLogger.detachAppender(appender);
        adviceLogger.setLevel(originalLevel);
        MDC.clear();
    }

    private static Message messageOnQueue(String queue, String correlationId) {
        MessageProperties properties = new MessageProperties();
        properties.setConsumerQueue(queue);
        if (correlationId != null) {
            properties.setHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, correlationId);
        }
        return new Message(new byte[0], properties);
    }

    private ILoggingEvent onlyCompletionLine() {
        assertEquals(1, appender.list.size(), "メッセージ1件につき完了行は1行");
        return appender.list.get(0);
    }

    @Test
    void ヘッダの無いメッセージには新しい処理IDを採番してMDCへ設定し処理後に除去する() throws Throwable {
        AtomicReference<String> mdcDuringInvocation = new AtomicReference<>();
        FakeMethodInvocation invocation = new FakeMethodInvocation(
                new Object[] {messageOnQueue("q", null)},
                () -> mdcDuringInvocation.set(MDC.get(CorrelationIdFilter.MDC_KEY)));

        advice.invoke(invocation);

        assertNotNull(mdcDuringInvocation.get(), "ヘッダが無くても処理IDが採番される");
        assertFalse(mdcDuringInvocation.get().isBlank());
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    @Test
    void ヘッダの無いメッセージごとに異なる処理IDを採番する() throws Throwable {
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();
        advice.invoke(new FakeMethodInvocation(new Object[] {messageOnQueue("q", null)},
                () -> first.set(MDC.get(CorrelationIdFilter.MDC_KEY))));
        advice.invoke(new FakeMethodInvocation(new Object[] {messageOnQueue("q", null)},
                () -> second.set(MDC.get(CorrelationIdFilter.MDC_KEY))));

        assertNotNull(first.get());
        assertNotEquals(first.get(), second.get());
    }

    @Test
    void ヘッダが空白なら新しい処理IDを採番する() throws Throwable {
        AtomicReference<String> seen = new AtomicReference<>();
        advice.invoke(new FakeMethodInvocation(new Object[] {messageOnQueue("q", "  ")},
                () -> seen.set(MDC.get(CorrelationIdFilter.MDC_KEY))));

        assertNotNull(seen.get());
        assertFalse(seen.get().isBlank());
    }

    @Test
    void Messageが引数に無くても処理IDを採番する() throws Throwable {
        AtomicReference<String> seen = new AtomicReference<>();
        advice.invoke(new FakeMethodInvocation(new Object[] {"not a message"},
                () -> seen.set(MDC.get(CorrelationIdFilter.MDC_KEY))));

        assertNotNull(seen.get());
        assertEquals("unknown", extractKv(onlyCompletionLine().getFormattedMessage(), "queue"));
    }

    @Test
    void 正常終了ではqueueとduration_msとoutcomeの完了行をINFOで1行出す() throws Throwable {
        advice.invoke(new FakeMethodInvocation(new Object[] {messageOnQueue("operation-logs.queue", "cid-1")}, () -> { }));

        ILoggingEvent event = onlyCompletionLine();
        String text = event.getFormattedMessage();
        assertEquals(Level.INFO, event.getLevel());
        assertEquals("operation-logs.queue", extractKv(text, "queue"));
        assertTrue(extractKv(text, "duration_ms").matches("\\d+"), text);
        assertEquals("success", extractKv(text, "outcome"));
        assertEquals("cid-1", event.getMDCPropertyMap().get(CorrelationIdFilter.MDC_KEY),
                "完了行の時点でも処理IDがMDCにある");
    }

    @Test
    void 例外で終わったときはWARNで失敗の完了行を出し例外は再送出する() throws Throwable {
        IllegalStateException failure = new IllegalStateException("boom\nsecret");
        FakeMethodInvocation invocation = new FakeMethodInvocation(
                new Object[] {messageOnQueue("error-logs.queue", "cid-2")}, () -> {
                    throw failure;
                });

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> advice.invoke(invocation));

        assertEquals(failure, thrown);
        ILoggingEvent event = onlyCompletionLine();
        String text = event.getFormattedMessage();
        assertTrue(event.getLevel().isGreaterOrEqual(Level.WARN));
        assertEquals("error-logs.queue", extractKv(text, "queue"));
        assertEquals("failure", extractKv(text, "outcome"));
        assertEquals("IllegalStateException", extractKv(text, "error"));
        assertFalse(text.contains("secret"), "例外メッセージは出さない");
        assertEquals("cid-2", event.getMDCPropertyMap().get(CorrelationIdFilter.MDC_KEY));
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY), "失敗でもMDCから除去される");
    }

    private static String extractKv(String text, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|\\s)" + key + "=(\\S+)").matcher(text);
        assertTrue(m.find(), key + "= が本文に無い: " + text);
        return m.group(1);
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
