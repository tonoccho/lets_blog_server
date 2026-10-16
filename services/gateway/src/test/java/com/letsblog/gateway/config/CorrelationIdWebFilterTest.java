package com.letsblog.gateway.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.WebFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorrelationIdWebFilterTest {

    private final CorrelationIdWebFilter filter = new CorrelationIdWebFilter();

    private Logger accessLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachLogAppender() {
        accessLogger = (Logger) LoggerFactory.getLogger(CorrelationIdWebFilter.class);
        appender = new ListAppender<>();
        appender.start();
        accessLogger.addAppender(appender);
    }

    @AfterEach
    void detachLogAppender() {
        accessLogger.detachAppender(appender);
    }

    private List<String> accessLogs() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void 通常のパスはアクセスログを出す() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/sites").build());

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty())).verifyComplete();

        assertEquals(1, accessLogs().size());
        String line = accessLogs().get(0);
        assertTrue(line.contains("method=GET path=/api/sites status=null"), line);
        assertTrue(line.contains("correlation_id="
                + exchange.getResponse().getHeaders().getFirst(CorrelationIdWebFilter.CORRELATION_ID_HEADER)), line);
    }

    @Test
    void 応答状態が決まっていればアクセスログに状態コードを出す() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/sites").build());

        StepVerifier.create(filter.filter(exchange, ex -> {
            ex.getResponse().setRawStatusCode(204);
            return Mono.empty();
        })).verifyComplete();

        assertTrue(accessLogs().get(0).contains("status=204"), accessLogs().get(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/api/jobs/1/stream", "/stream"})
    void ヘルスチェックとストリームのパスはアクセスログを出さない(String path) {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path).build());

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty())).verifyComplete();

        assertTrue(accessLogs().isEmpty(), accessLogs().toString());
        assertNotNull(exchange.getResponse().getHeaders().getFirst(CorrelationIdWebFilter.CORRELATION_ID_HEADER));
    }

    @Test
    void ストリームでなくても部分一致だけではログを省略しない() {
        ServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/streams/actuator").build());

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty())).verifyComplete();

        assertEquals(1, accessLogs().size());
    }

    @Test
    void メソッドが取得できない要求はUNKNOWNとしてログに出す() {
        ServerWebExchange base = MockServerWebExchange.from(MockServerHttpRequest.get("/api/sites").build());
        ServerHttpRequest noMethod = new ServerHttpRequestDecorator(base.getRequest()) {
            @Override
            public HttpMethod getMethod() {
                return null;
            }

            @Override
            public ServerHttpRequest.Builder mutate() {
                // 既定のmutate()はこのdecoratorを元に組み立て、メソッドnullを拒否する。委譲先から組み立てる。
                return getDelegate().mutate();
            }
        };
        ServerWebExchange exchange = base.mutate().request(noMethod).build();

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty())).verifyComplete();

        assertTrue(accessLogs().get(0).contains("method=UNKNOWN"), accessLogs().get(0));
    }

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
