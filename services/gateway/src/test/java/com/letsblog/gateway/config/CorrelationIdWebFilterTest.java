package com.letsblog.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.WebFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CorrelationIdWebFilterTest {

    private final CorrelationIdWebFilter filter = new CorrelationIdWebFilter();

    @Test
    void ヘッダが無ければ新しい相関IDを生成し下流リクエストとレスポンスの両方に設定する() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/sites").build());
        AtomicReference<String> downstreamHeader = new AtomicReference<>();
        WebFilterChain chain = ex -> {
            downstreamHeader.set(ex.getRequest().getHeaders().getFirst(CorrelationIdWebFilter.CORRELATION_ID_HEADER));
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        String responseHeader = exchange.getResponse().getHeaders().getFirst(CorrelationIdWebFilter.CORRELATION_ID_HEADER);
        assertNotNull(responseHeader);
        assertFalse(responseHeader.isBlank());
        assertEquals(responseHeader, downstreamHeader.get());
    }

    @Test
    void ヘッダがあればその値をそのまま引き継ぐ() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/sites")
                        .header(CorrelationIdWebFilter.CORRELATION_ID_HEADER, "given-correlation-id")
                        .build());
        WebFilterChain chain = ex -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals("given-correlation-id",
                exchange.getResponse().getHeaders().getFirst(CorrelationIdWebFilter.CORRELATION_ID_HEADER));
    }
}
