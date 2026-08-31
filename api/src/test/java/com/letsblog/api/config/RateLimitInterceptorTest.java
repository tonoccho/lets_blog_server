package com.letsblog.api.config;

import com.letsblog.api.service.AppSettingService;
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
    private AppSettingService appSettingService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    private RateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new RateLimitInterceptor(rateLimiterRegistry, appSettingService);
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
    @DisplayName("Should use global rate limiter for setup-status (read-only, not brute-force-able)")
    void testSetupStatusUsesGlobalRateLimiter() {
        when(request.getRequestURI()).thenReturn("/api/auth/setup-status");
        when(rateLimiter.acquirePermission()).thenReturn(true);

        interceptor.preHandle(request, response, null);

        verify(rateLimiterRegistry).rateLimiter("api-global");
    }

    @Test
    @DisplayName("Should use global rate limiter for totp/status (read-only, not brute-force-able)")
    void testTotpStatusUsesGlobalRateLimiter() {
        when(request.getRequestURI()).thenReturn("/api/auth/totp/status");
        when(rateLimiter.acquirePermission()).thenReturn(true);

        interceptor.preHandle(request, response, null);

        verify(rateLimiterRegistry).rateLimiter("api-global");
    }

    @Test
    @DisplayName("Should still use auth rate limiter for setup (creates a privileged account)")
    void testSetupEndpointUsesAuthRateLimiter() {
        when(request.getRequestURI()).thenReturn("/api/auth/setup");
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

    @Test
    @DisplayName("Should use upload rate limiter for AI image generation (issue #442)")
    void testAiImageGenerationUsesUploadRateLimiter() {
        when(request.getRequestURI()).thenReturn("/api/ai/image");
        when(rateLimiter.acquirePermission()).thenReturn(true);

        interceptor.preHandle(request, response, null);

        verify(rateLimiterRegistry).rateLimiter("upload-endpoint");
    }

    @Test
    @DisplayName("Should use global rate limiter for AI image options, not upload (issue #442)")
    void testAiImageOptionsUsesGlobalRateLimiter() {
        when(request.getRequestURI()).thenReturn("/api/ai/image-options");
        when(rateLimiter.acquirePermission()).thenReturn(true);

        interceptor.preHandle(request, response, null);

        verify(rateLimiterRegistry).rateLimiter("api-global");
    }

    @Test
    @DisplayName("Should use global rate limiter for image generation prompt defaults (issue #442)")
    void testImageGenerationPromptDefaultsUsesGlobalRateLimiter() {
        when(request.getRequestURI()).thenReturn("/api/projects/5/image-generation-prompt-defaults");
        when(rateLimiter.acquirePermission()).thenReturn(true);

        interceptor.preHandle(request, response, null);

        verify(rateLimiterRegistry).rateLimiter("api-global");
    }

    @Test
    @DisplayName("Should use global rate limiter for image generation size defaults (issue #442)")
    void testImageGenerationSizeDefaultsUsesGlobalRateLimiter() {
        when(request.getRequestURI()).thenReturn("/api/projects/5/image-generation-size-defaults");
        when(rateLimiter.acquirePermission()).thenReturn(true);

        interceptor.preHandle(request, response, null);

        verify(rateLimiterRegistry).rateLimiter("api-global");
    }

    @Test
    @DisplayName("Should still use upload rate limiter for asset image upload (issue #442)")
    void testAssetImageUploadUsesUploadRateLimiter() {
        when(request.getRequestURI()).thenReturn("/api/projects/5/asset-images/12/upload");
        when(rateLimiter.acquirePermission()).thenReturn(true);

        interceptor.preHandle(request, response, null);

        verify(rateLimiterRegistry).rateLimiter("upload-endpoint");
    }

    @Test
    @DisplayName("Should use dedicated rate limiter for operation-logs, not api-global (issue #464)")
    void testOperationLogsUsesDedicatedRateLimiter() {
        when(request.getRequestURI()).thenReturn("/api/operation-logs");
        when(rateLimiter.acquirePermission()).thenReturn(true);

        interceptor.preHandle(request, response, null);

        verify(rateLimiterRegistry).rateLimiter("operation-log-endpoint");
    }

    @Test
    @DisplayName("Should use dedicated rate limiter for operation-logs sub-paths (issue #464)")
    void testOperationLogsUnifiedUsesDedicatedRateLimiter() {
        when(request.getRequestURI()).thenReturn("/api/operation-logs/unified");
        when(rateLimiter.acquirePermission()).thenReturn(true);

        interceptor.preHandle(request, response, null);

        verify(rateLimiterRegistry).rateLimiter("operation-log-endpoint");
    }

    @Test
    @DisplayName("Should apply the configured upload rate limit before checking permission (issue #444)")
    void testUploadEndpointAppliesConfiguredLimit() {
        when(request.getRequestURI()).thenReturn("/api/ai/image");
        when(appSettingService.getUploadRateLimitRequests()).thenReturn(25);
        when(rateLimiter.acquirePermission()).thenReturn(true);

        boolean result = interceptor.preHandle(request, response, null);

        assertTrue(result);
        verify(rateLimiter).changeLimitForPeriod(25);
        verify(rateLimiter).acquirePermission();
    }

    @Test
    @DisplayName("Should bypass the rate limit entirely when upload rate limit is set to unlimited (issue #444)")
    void testUploadEndpointBypassesCheckWhenUnlimited() {
        when(request.getRequestURI()).thenReturn("/api/ai/image");
        when(appSettingService.getUploadRateLimitRequests()).thenReturn(AppSettingService.UNLIMITED);

        boolean result = interceptor.preHandle(request, response, null);

        assertTrue(result);
        verify(rateLimiter, never()).changeLimitForPeriod(anyInt());
        verify(rateLimiter, never()).acquirePermission();
    }

    @Test
    @DisplayName("Should not consult the upload rate limit setting for non-upload endpoints (issue #444)")
    void testNonUploadEndpointDoesNotConsultUploadRateLimitSetting() {
        when(request.getRequestURI()).thenReturn("/api/posts");
        when(rateLimiter.acquirePermission()).thenReturn(true);

        interceptor.preHandle(request, response, null);

        verify(appSettingService, never()).getUploadRateLimitRequests();
    }
}
