package com.letsblog.api.config;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

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
        if (requestPath.contains("/auth/") || requestPath.contains("/login") || requestPath.contains("/register")) {
            return "auth-endpoint";
        } else if (requestPath.contains("/upload") || requestPath.contains("/image")) {
            return "upload-endpoint";
        }
        return "api-global";
    }
}
