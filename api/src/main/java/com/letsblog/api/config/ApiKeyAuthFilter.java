package com.letsblog.api.config;

import com.letsblog.api.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * VSCode拡張/Webフロントからのリクエストをユーザー単位のAPIキー(ヘッダ X-API-Key)で検証する。
 * キーはログイン(/api/auth/login, /api/auth/totp/verify)成功時にユーザーごとに発行され、
 * api_keys テーブルにハッシュ化して保存される。ログイン自体やサインアップ等、
 * まだキーを持たない状態でも到達できる必要があるエンドポイントはPUBLIC_AUTH_PATHSで除外する。
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-Key";

    private static final Set<String> PUBLIC_AUTH_PATHS = Set.of(
            "/api/auth/login",
            "/api/auth/totp/verify",
            "/api/auth/signup",
            "/api/auth/setup",
            "/api/auth/setup-status",
            "/api/auth/password-reset/request",
            "/api/auth/password-reset/confirm");

    private final ApiKeyService apiKeyService;

    public ApiKeyAuthFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || path.equals("/api/health") || PUBLIC_AUTH_PATHS.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String providedKey = request.getHeader(API_KEY_HEADER);
        if (providedKey == null || apiKeyService.resolveUserId(providedKey).isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"invalid or missing X-API-Key\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
