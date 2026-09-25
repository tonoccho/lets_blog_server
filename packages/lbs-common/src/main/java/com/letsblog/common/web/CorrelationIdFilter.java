package com.letsblog.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * サービス間で相関IDを引き継ぐためのフィルタ。リクエストヘッダ {@value #CORRELATION_ID_HEADER}
 * が付与されていればそれを、なければ新規UUIDを相関IDとして採用し、MDCとレスポンスヘッダに設定する。
 * 分散トレーシング整備(#582)でサービス間伝播・構造化ログ出力に使う。各サービスが
 * {@code @Bean}として登録する(gatewayのCorrelationIdWebFilterと同じ役割のservlet版)。
 *
 * <p>{@link Ordered#HIGHEST_PRECEDENCE}で、Spring Securityのフィルタチェーンより前に実行されるよう
 * 明示的に順序付けている。認証エラーのログにも相関IDが載るようにするため。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
