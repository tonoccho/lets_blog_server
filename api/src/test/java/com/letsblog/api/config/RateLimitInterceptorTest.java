package com.letsblog.api.config;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Rate Limit Interceptor Tests")
class RateLimitInterceptorTest {

    @Mock
    private RateLimiterRegistry rateLimiterRegistry;

    @Mock
    private RateLimiter rateLimiter;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    private RateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new RateLimitInterceptor(rateLimiterRegistry);
        when(rateLimiterRegistry.rateLimiter(anyString())).thenReturn(rateLimiter);
    }

    @Test
    @DisplayName("Should allow request when rate limit not exceeded")
    void testAllowRequestWhenNotExceeded() {
        when(request.getRequestURI()).thenReturn("/api/sites");
        when(rateLimiter.acquirePermission()).thenReturn(true);

        boolean result = interceptor.preHandle(request, response, null);

        assertTrue(result);
        verify(rateLimiter).acquirePermission();
    }

    @Test
    @DisplayName("Should reject request when rate limit exceeded")
    void testRejectRequestWhenExceeded() {
        when(request.getRequestURI()).thenReturn("/api/posts");
        when(rateLimiter.acquirePermission()).thenReturn(false);

        boolean result = interceptor.preHandle(request, response, null);

        assertFalse(result);
        verify(response).setStatus(429);
        verify(response).setHeader("Retry-After", "60");
    }

    @Test
    @DisplayName("Should use auth rate limiter for login endpoint")
    void testAuthEndpointUsesAuthRateLimiter() {
        when(request.getRequestURI()).thenReturn("/api/auth/login");
        when(rateLimiter.acquirePermission()).thenReturn(true);

        interceptor.preHandle(request, response, null);

        verify(rateLimiterRegistry).rateLimiter("auth-endpoint");
    }

    @Test
    @DisplayName("Should use upload rate limiter for upload endpoint")
    void testUploadEndpointUsesUploadRateLimiter() {
        when(request.getRequestURI()).thenReturn("/api/upload");
        when(rateLimiter.acquirePermission()).thenReturn(true);

        interceptor.preHandle(request, response, null);

        verify(rateLimiterRegistry).rateLimiter("upload-endpoint");
    }

    @Test
    @DisplayName("Should use global rate limiter for other endpoints")
    void testOtherEndpointsUseGlobalRateLimiter() {
        when(request.getRequestURI()).thenReturn("/api/projects");
        when(rateLimiter.acquirePermission()).thenReturn(true);

        interceptor.preHandle(request, response, null);

        verify(rateLimiterRegistry).rateLimiter("api-global");
    }
}
