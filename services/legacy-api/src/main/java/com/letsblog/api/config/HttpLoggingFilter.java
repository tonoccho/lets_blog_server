package com.letsblog.api.config;

import com.letsblog.common.web.CorrelationIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class HttpLoggingFilter extends OncePerRequestFilter {

    private static final long REQUEST_LOG_THRESHOLD_MS = 100;
    private static final int REQUEST_BODY_CACHE_LIMIT = Integer.MAX_VALUE;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (isSkipLogging(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        ContentCachingRequestWrapper requestWrapper =
                new ContentCachingRequestWrapper(request, REQUEST_BODY_CACHE_LIMIT);
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);

        long startTime = System.currentTimeMillis();
        try {
            filterChain.doFilter(requestWrapper, responseWrapper);
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            logHttpRequest(requestWrapper, responseWrapper, duration);
            responseWrapper.copyBodyToResponse();
        }
    }

    private void logHttpRequest(ContentCachingRequestWrapper request, ContentCachingResponseWrapper response, long duration) {
        try {
            String method = request.getMethod();
            String path = request.getRequestURI();
            String queryString = request.getQueryString();
            int status = response.getStatus();

            Map<String, Object> logContext = new HashMap<>();
            logContext.put("correlation_id", MDC.get(CorrelationIdFilter.MDC_KEY));
            logContext.put("method", method);
            logContext.put("path", path);
            logContext.put("query", queryString);
            logContext.put("status", status);
            logContext.put("duration_ms", duration);
            logContext.put("content_type", request.getContentType());
            logContext.put("remote_addr", getClientIp(request));
            logContext.put("user_agent", request.getHeader("User-Agent"));

            String requestBody = getRequestBody(request);
            if (!requestBody.isEmpty() && !isFileUpload(request)) {
                logContext.put("request_body", requestBody);
            }

            if (duration > REQUEST_LOG_THRESHOLD_MS || status >= 400) {
                String responseBody = getResponseBody(response);
                if (!responseBody.isEmpty() && isJsonResponse(response)) {
                    logContext.put("response_body", responseBody);
                }
            }

            if (status >= 500) {
                log.error("HTTP request - {}", logContext);
            } else if (status >= 400) {
                log.warn("HTTP request - {}", logContext);
            } else if (duration > REQUEST_LOG_THRESHOLD_MS) {
                log.info("HTTP request - {}", logContext);
            } else {
                log.debug("HTTP request - {}", logContext);
            }
        } catch (Exception e) {
            log.debug("Error logging HTTP request", e);
        }
    }

    private String getRequestBody(ContentCachingRequestWrapper request) {
        byte[] content = request.getContentAsByteArray();
        if (content.length == 0) {
            return "";
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    private String getResponseBody(ContentCachingResponseWrapper response) {
        byte[] content = response.getContentAsByteArray();
        if (content.length == 0) {
            return "";
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty()) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty()) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }

    private boolean isFileUpload(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null && contentType.contains("multipart/form-data");
    }

    private boolean isJsonResponse(HttpServletResponse response) {
        String contentType = response.getContentType();
        return contentType != null && contentType.contains("application/json");
    }

    private boolean isSkipLogging(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/health") ||
               path.startsWith("/api/metrics") ||
               path.startsWith("/v3/api-docs") ||
               path.startsWith("/swagger-ui") ||
               path.equals("/") ||
               path.isEmpty() ||
               // SSEエンドポイント: ContentCachingResponseWrapperで包むと、非同期処理の
               // 開始直後にfinallyブロックのcopyBodyToResponse()がその時点のバッファ内容だけで
               // レスポンスをContent-Length付きでコミットしてしまい、後続のイベント配信が
               // 届く前にストリームが終了したとクライアントに誤認させてしまう(issue #198)。
               path.endsWith("/stream");
    }
}
