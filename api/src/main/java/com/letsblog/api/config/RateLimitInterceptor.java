package com.letsblog.api.config;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    /**
     * 認証情報を扱わない読み取り専用の状態確認エンドポイント。ブルートフォース対策の
     * 対象ではないため、他の/auth/配下のエンドポイントと同じ厳しいauth-endpointバケットを
     * 共有させない(共有させると、画面表示のたびに呼ばれるこれらの呼び出しだけで枠を使い切り、
     * 本来保護すべきログイン・セットアップ自体がブロックされてしまう。issue #321)。
     */
    private static final Set<String> AUTH_STATUS_CHECK_PATHS =
            Set.of("/api/auth/setup-status", "/api/auth/totp/status");

    private final RateLimiterRegistry rateLimiterRegistry;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String requestPath = request.getRequestURI();
        String rateLimiterName = getRateLimiterName(requestPath);

        RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter(rateLimiterName);

        if (!rateLimiter.acquirePermission()) {
            log.warn("Rate limit exceeded for {} (limiter: {})", requestPath, rateLimiterName);
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            return false;
        }

        return true;
    }

    private String getRateLimiterName(String requestPath) {
        if (AUTH_STATUS_CHECK_PATHS.contains(requestPath)) {
            return "api-global";
        } else if (requestPath.contains("/auth/") || requestPath.contains("/login") || requestPath.contains("/register")) {
            return "auth-endpoint";
        } else if (requestPath.contains("/upload") || requestPath.contains("/image")) {
            return "upload-endpoint";
        }
        return "api-global";
    }
}
