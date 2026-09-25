package com.letsblog.common.messaging;

import com.letsblog.common.web.CorrelationIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CorrelationIdMessagePostProcessorTest {

    private final CorrelationIdMessagePostProcessor postProcessor = new CorrelationIdMessagePostProcessor();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void MDCに相関IDがあればメッセージヘッダへ設定する() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "test-correlation-id");
        Message message = new Message(new byte[0], new MessageProperties());

        Message result = postProcessor.postProcessMessage(message);

        assertEquals("test-correlation-id",
                result.getMessageProperties().getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER));
    }

    @Test
    void MDCに相関IDが無ければヘッダを設定しない() {
        Message message = new Message(new byte[0], new MessageProperties());

        Message result = postProcessor.postProcessMessage(message);

        assertNull(result.getMessageProperties().getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER));
    }
}
