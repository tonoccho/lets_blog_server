package com.letsblog.gateway.config;

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

/**
 * サービス間で相関IDを引き継ぐためのフィルタ(#560。C13の基点)。
 * リクエストヘッダ {@value #CORRELATION_ID_HEADER} が付与されていればそれを、
 * なければ新規UUIDを採番し、下流サービスへのリクエストとクライアントへのレスポンスの
 * 両方にヘッダとして設定する。gatewayが全リクエストの唯一の入口になったことで、
 * ここで一箇所に採番を集約できる(lbs-commonのCorrelationIdFilterはServlet
 * ベースのため、reactive(WebFlux)実装のgatewayではそのまま使えず、同等の実装を
 * ここに個別に用意している)。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdWebFilter implements WebFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String correlationId = request.getHeaders().getFirst(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        String resolvedCorrelationId = correlationId;

        ServerHttpRequest mutatedRequest = request.mutate()
                .header(CORRELATION_ID_HEADER, resolvedCorrelationId)
                .build();
        ServerHttpResponse response = exchange.getResponse();
        response.getHeaders().set(CORRELATION_ID_HEADER, resolvedCorrelationId);

        ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();
        return chain.filter(mutatedExchange);
    }
}
