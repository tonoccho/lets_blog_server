package com.letsblog.gateway.config;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * レート制限(#560。旧legacy-apiのRateLimitInterceptorから移設)。バケット分類ロジックは
 * 移設元と同一だが、アップロード系エンドポイントを管理画面から動的に変更する機能
 * (旧AppSettingService経由のDB設定)は、gatewayがDBを持たない設計のため対応していない
 * (静的なデフォルト値のみ。follow-up issueで再検討する)。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RateLimitWebFilter implements WebFilter {

    private static final String UPLOAD_ENDPOINT = "upload-endpoint";
    private static final String OPERATION_LOG_ENDPOINT = "operation-log-endpoint";
    private static final String AUTH_ENDPOINT = "auth-endpoint";
    private static final String API_GLOBAL = "api-global";

    private static final String OPERATION_LOG_PATH = "/api/operation-logs";

    private static final Set<String> AUTH_STATUS_CHECK_PATHS =
            Set.of("/api/auth/setup-status", "/api/auth/totp/status");

    private static final Set<String> LIGHTWEIGHT_IMAGE_METADATA_PATH_SUFFIXES = Set.of(
            "/image-options", "/image-generation-prompt-defaults", "/image-generation-size-defaults");

    private final ConcurrentHashMap<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();
    private final RateLimitProperties properties;

    public RateLimitWebFilter(RateLimitProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        String rateLimiterName = getRateLimiterName(path);
        RateLimiter rateLimiter = rateLimiters.computeIfAbsent(rateLimiterName, this::createRateLimiter);

        if (!rateLimiter.acquirePermission()) {
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            response.getHeaders().set("Retry-After", "60");
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            byte[] body = "{\"error\":\"Rate limit exceeded\"}".getBytes(StandardCharsets.UTF_8);
            DataBuffer buffer = response.bufferFactory().wrap(body);
            return response.writeWith(Mono.just(buffer));
        }

        return chain.filter(exchange);
    }

    private RateLimiter createRateLimiter(String name) {
        RateLimitProperties.Bucket bucket = switch (name) {
            case UPLOAD_ENDPOINT -> properties.getUploadEndpoint();
            case OPERATION_LOG_ENDPOINT -> properties.getOperationLogEndpoint();
            case AUTH_ENDPOINT -> properties.getAuthEndpoint();
            default -> properties.getApiGlobal();
        };
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(bucket.getLimitForPeriod())
                .limitRefreshPeriod(bucket.getLimitRefreshPeriod())
                .timeoutDuration(Duration.ZERO)
                .build();
        return RateLimiter.of(name, config);
    }

    private String getRateLimiterName(String requestPath) {
        if (requestPath.startsWith(OPERATION_LOG_PATH)) {
            return OPERATION_LOG_ENDPOINT;
        } else if (AUTH_STATUS_CHECK_PATHS.contains(requestPath)) {
            return API_GLOBAL;
        } else if (requestPath.contains("/auth/")
                || requestPath.contains("/login")
                || requestPath.contains("/register")) {
            return AUTH_ENDPOINT;
        } else if (isLightweightImageMetadataPath(requestPath)) {
            return API_GLOBAL;
        } else if (requestPath.contains("/upload") || requestPath.contains("/image")) {
            return UPLOAD_ENDPOINT;
        }
        return API_GLOBAL;
    }

    private boolean isLightweightImageMetadataPath(String requestPath) {
        return LIGHTWEIGHT_IMAGE_METADATA_PATH_SUFFIXES.stream().anyMatch(requestPath::endsWith);
    }
}
