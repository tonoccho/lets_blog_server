package com.letsblog.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * サービス間で相関IDを引き継ぐためのフィルタ(#560。C13の基点)。
 * リクエストヘッダ {@value #CORRELATION_ID_HEADER} が付与されていればそれを、
 * なければ新規UUIDを採番し、下流サービスへのリクエストとクライアントへのレスポンスの
 * 両方にヘッダとして設定する。gatewayが全リクエストの唯一の入口になったことで、
 * ここで一箇所に採番を集約できる(lbs-commonのCorrelationIdFilterはServlet
 * ベースのため、reactive(WebFlux)実装のgatewayではそのまま使えず、同等の実装を
 * ここに個別に用意している)。
 *
 * <p>相関IDでリクエストの全経路をgrepで追えるようにする(issue #582の受入基準)には、
 * gateway自身のログにも相関IDが必要なため、リクエスト完了時に最小限のアクセスログを
 * 出力する。WebFluxはリクエストごとにスレッドが固定されないため、下流サービスのように
 * MDC(ThreadLocal)には頼らず、ログ出力に必要な値をこのフィルタのローカル変数として
 * 直接渡す。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdWebFilter.class);

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    /** 受け入れる処理IDの形式。これに合わない値(改行・過長・許可外の文字)は捨てて採番し直す。 */
    private static final Pattern ACCEPTED_FORMAT = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String correlationId = request.getHeaders().getFirst(CORRELATION_ID_HEADER);
        // 捨てた値はログ偽造の元になりうるため、どこにも出さない。
        if (correlationId == null || !ACCEPTED_FORMAT.matcher(correlationId).matches()) {
            correlationId = UUID.randomUUID().toString();
        }
        String resolvedCorrelationId = correlationId;

        ServerHttpRequest mutatedRequest = request.mutate()
                .header(CORRELATION_ID_HEADER, resolvedCorrelationId)
                .build();
        ServerHttpResponse response = exchange.getResponse();
        response.getHeaders().set(CORRELATION_ID_HEADER, resolvedCorrelationId);

        ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();
        long startTime = System.currentTimeMillis();
        String method = request.getMethod() != null ? request.getMethod().name() : "UNKNOWN";
        String path = request.getPath().value();
        return chain.filter(mutatedExchange)
                .doFinally(signalType -> logAccess(method, path, response, resolvedCorrelationId, startTime));
    }

    private void logAccess(
            String method, String path, ServerHttpResponse response, String correlationId, long startTime) {
        if (isSkipLogging(path)) {
            return;
        }
        long durationMs = System.currentTimeMillis() - startTime;
        Integer status = response.getStatusCode() != null ? response.getStatusCode().value() : null;
        log.info("gateway request: method={} path={} status={} duration_ms={} correlation_id={}",
                method, path, status, durationMs, correlationId);
    }

    private boolean isSkipLogging(String path) {
        return path.startsWith("/actuator") || path.endsWith("/stream");
    }
}
