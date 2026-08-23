package com.letsblog.api.config;

import com.letsblog.api.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * VSCode拡張/Webフロントからのリクエストを認証する。
 *
 * <p>従来はユーザー単位のAPIキー(ヘッダ X-API-Key)のみを検証していた。キーはログイン
 * (/api/auth/login, /api/auth/totp/verify)成功時にユーザーごとに発行され、api_keys テーブルに
 * ハッシュ化して保存される。ログイン自体やサインアップ等、まだキーを持たない状態でも
 * 到達できる必要があるエンドポイントはPUBLIC_AUTH_PATHSで除外する。
 *
 * <p>issue #564で、Authorizationヘッダーの検証済みKeycloak JWTを代替の認証手段として受理する
 * ようにした(Web/VSCode拡張のいずれかが段階的にX-API-KeyからBearerトークンへ移行するため、
 * 既存のX-API-Key経路は変更・弱体化せず両方を受理する)。
 *
 * <p>この{@link JwtDecoder}による検証は、SecurityConfigのoauth2ResourceServer().jwt()が使う
 * ものと同じBean(JwtDecoderConfig参照。KEYCLOAK_JWK_SET_URI/KEYCLOAK_ISSUERで署名・有効期限・
 * issuerを検証する)を再利用する。このフィルタは{@code @Component}として自動登録される
 * 素朴なservletフィルタであり、Spring Securityの{@code FilterChainProxy}(oauth2ResourceServer
 * のBearerトークン認証を含む)より後に実行される保証も先に実行される保証も無い
 * (デフォルトのフィルタ登録順は未指定。実機検証(#564)で確認済み)。そのため、
 * {@code SecurityContextHolder}に認証結果が既に入っている前提を置かず、このフィルタ自身が
 * Authorizationヘッダーを独立して検証する自己完結な実装にしている。こうすることで、
 * 実行順序がどちらであっても「有効なJWTか、有効なX-API-Keyかのいずれかがあれば通す」
 * という判定が正しく機能する(なお、Spring Security側のoauth2ResourceServer().jwt()自体は
 * このフィルタの前後どちらで動いても必ず一度は実行されるため、CurrentActorServiceが読む
 * SecurityContext上のJwtAuthenticationTokenは、このフィルタがAuthorizationヘッダーを
 * 独自検証するかどうかに関わらず正しく設定される)。
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private static final Set<String> PUBLIC_AUTH_PATHS = Set.of(
            "/api/auth/login",
            "/api/auth/totp/verify",
            "/api/auth/signup",
            "/api/auth/setup",
            "/api/auth/setup-status",
            "/api/auth/password-reset/request",
            "/api/auth/password-reset/confirm");

    private final ApiKeyService apiKeyService;
    private final JwtDecoder jwtDecoder;

    public ApiKeyAuthFilter(ApiKeyService apiKeyService, JwtDecoder jwtDecoder) {
        this.apiKeyService = apiKeyService;
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || path.equals("/api/health") || PUBLIC_AUTH_PATHS.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (hasValidBearerToken(request) || hasValidApiKey(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"invalid or missing X-API-Key\"}");
    }

    private boolean hasValidApiKey(HttpServletRequest request) {
        String providedKey = request.getHeader(API_KEY_HEADER);
        return providedKey != null && apiKeyService.resolveUserId(providedKey).isPresent();
    }

    private boolean hasValidBearerToken(HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return false;
        }
        String token = header.substring(BEARER_PREFIX.length());
        try {
            jwtDecoder.decode(token);
            return true;
        } catch (JwtException e) {
            log.debug("Authorizationヘッダーの検証に失敗したため、X-API-Keyへフォールバックする", e);
            return false;
        }
    }
}
