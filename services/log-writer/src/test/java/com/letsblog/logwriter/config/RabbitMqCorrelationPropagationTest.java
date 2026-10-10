package com.letsblog.logwriter.config;

import static com.letsblog.common.web.CorrelationIdFilter.CORRELATION_ID_HEADER;
import static com.letsblog.common.web.CorrelationIdFilter.MDC_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

import com.letsblog.common.messaging.CorrelationIdListenerAdvice;
import com.letsblog.common.messaging.CorrelationIdMessagePostProcessor;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;
import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * issue #1735: log-writerが発行するメッセージに処理ID(X-Correlation-Id)のヘッダが付き、
 * 受信側の処理中のログと同じ処理IDになること、およびこのサービスの全RabbitTemplate・全listener
 * container factoryが処理IDの仕組みを持つことを、実ブローカー無しで検証する。
 * (OperationLogService/FrontendErrorLogServiceはBeanのRabbitTemplateで{@code convertAndSend}する。)
 */
class RabbitMqCorrelationPropagationTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Configuration
    @Import(RabbitMqConfig.class)
    static class Collaborators {
        @Bean
        ConnectionFactory connectionFactory() {
            return mock(ConnectionFactory.class);
        }

        @Bean
        SimpleRabbitListenerContainerFactoryConfigurer configurer() {
            return mock(SimpleRabbitListenerContainerFactoryConfigurer.class);
        }
    }

    @Test
    void 全RabbitTemplateが処理IDのPostProcessorを持ち発行ヘッダに処理IDが付いて受信側のMDCへ届く() throws Throwable {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Collaborators.class)) {
            Collection<RabbitTemplate> templates = context.getBeansOfType(RabbitTemplate.class).values();
            assertFalse(templates.isEmpty(), "log-writerのRabbitTemplate(自前のBean)が無い");

            for (RabbitTemplate template : templates) {
                @SuppressWarnings("unchecked")
                Collection<MessagePostProcessor> processors =
                        (Collection<MessagePostProcessor>) ReflectionTestUtils.getField(template, "beforePublishPostProcessors");
                assertFalse(processors == null || processors.stream()
                        .noneMatch(CorrelationIdMessagePostProcessor.class::isInstance),
                        "RabbitTemplateにCorrelationIdMessagePostProcessorが登録されていない");

                MDC.put(MDC_KEY, "cid-1735");
                Message published = new Message(new byte[0], new MessageProperties());
                for (MessagePostProcessor processor : processors) {
                    published = processor.postProcessMessage(published);
                }
                MDC.clear();
                assertEquals("cid-1735", published.getMessageProperties().getHeader(CORRELATION_ID_HEADER));

                AtomicReference<String> mdcOnReceive = new AtomicReference<>();
                SimpleRabbitListenerContainerFactory factory = context.getBean(SimpleRabbitListenerContainerFactory.class);
                for (Advice advice : factory.getAdviceChain()) {
                    if (advice instanceof CorrelationIdListenerAdvice interceptor) {
                        interceptor.invoke(new Invocation(published, () -> mdcOnReceive.set(MDC.get(MDC_KEY))));
                    }
                }
                assertEquals("cid-1735", mdcOnReceive.get());
            }
        }
    }

    @Test
    void 全listenerContainerFactoryのadviceに処理IDのadviceがある() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Collaborators.class)) {
            Collection<SimpleRabbitListenerContainerFactory> factories =
                    context.getBeansOfType(SimpleRabbitListenerContainerFactory.class).values();
            assertFalse(factories.isEmpty());
            for (SimpleRabbitListenerContainerFactory factory : factories) {
                assertFalse(factory.getAdviceChain() == null || java.util.Arrays.stream(factory.getAdviceChain())
                        .noneMatch(CorrelationIdListenerAdvice.class::isInstance));
            }
        }
    }

    private record Invocation(Message message, Runnable onProceed) implements MethodInvocation {
        @Override
        public Object proceed() {
            onProceed.run();
            return null;
        }

        @Override
        public Object[] getArguments() {
            return new Object[] {message};
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
