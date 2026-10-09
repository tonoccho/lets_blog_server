package com.letsblog.gateway.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.WebFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private static final String UUID_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @ParameterizedTest
    @ValueSource(strings = {
            "line1\nforged log line",
            "has space",
            "under_score",
            "日本語",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "   "
    })
    void 形式に合わない値は捨てて新しいUUIDを採番し下流とレスポンスへ設定する(String invalid) {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/sites")
                        .header(CorrelationIdWebFilter.CORRELATION_ID_HEADER, invalid)
                        .build());
        AtomicReference<String> downstreamHeader = new AtomicReference<>();
        WebFilterChain chain = ex -> {
            downstreamHeader.set(ex.getRequest().getHeaders().getFirst(CorrelationIdWebFilter.CORRELATION_ID_HEADER));
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        String responseHeader = exchange.getResponse().getHeaders().getFirst(CorrelationIdWebFilter.CORRELATION_ID_HEADER);
        assertNotEquals(invalid, responseHeader);
        assertTrue(responseHeader.matches(UUID_PATTERN), responseHeader);
        assertEquals(responseHeader, downstreamHeader.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "a",
            "at17-1700000000000-abc123xyz",
            "123e4567-e89b-12d3-a456-426614174000",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    })
    void 形式どおりの値は1文字から64文字までそのまま引き継ぐ(String valid) {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/sites")
                        .header(CorrelationIdWebFilter.CORRELATION_ID_HEADER, valid)
                        .build());

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty())).verifyComplete();

        assertEquals(valid, exchange.getResponse().getHeaders().getFirst(CorrelationIdWebFilter.CORRELATION_ID_HEADER));
    }
}
