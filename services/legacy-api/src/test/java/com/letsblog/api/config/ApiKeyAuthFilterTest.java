package com.letsblog.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.letsblog.api.service.ApiKeyService;
import com.letsblog.common.testfixtures.JwtTestFixtures;
import jakarta.servlet.FilterChain;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * issue #564: {@link ApiKeyAuthFilter}がX-API-KeyとAuthorizationヘッダーの検証済みJWTの
 * どちらも代替の認証手段として受理すること、いずれも無ければ401にすることを検証する。
 */
class ApiKeyAuthFilterTest {

    private static final String VALID_API_KEY = "lb_valid-key";

    private ApiKeyService apiKeyService;
    private JwtDecoder jwtDecoder;
    private ApiKeyAuthFilter filter;

    @BeforeEach
    void setUp() {
        apiKeyService = mock(ApiKeyService.class);
        jwtDecoder = mock(JwtDecoder.class);
        filter = new ApiKeyAuthFilter(apiKeyService, jwtDecoder);
        when(apiKeyService.resolveUserId(VALID_API_KEY)).thenReturn(Optional.of(1L));
        when(apiKeyService.resolveUserId(org.mockito.ArgumentMatchers.argThat(k -> k == null || !k.equals(VALID_API_KEY))))
                .thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("有効なX-API-Keyがあれば通す(従来どおり)")
    void 有効なAPIキーは通す() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sites");
        request.addHeader("X-API-Key", VALID_API_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isNotEqualTo(401);
    }

    @Test
    @DisplayName("有効なAuthorization: Bearer JWTがあればX-API-Key無しでも通す(issue #564で追加)")
    void 有効なBearerトークンは通す() throws Exception {
        Jwt jwt = JwtTestFixtures.jwt("keycloak-sub-1", "user");
        when(jwtDecoder.decode("valid-jwt")).thenReturn(jwt);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sites");
        request.addHeader("Authorization", "Bearer valid-jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isNotEqualTo(401);
    }

    @Test
    @DisplayName("Authorizationヘッダーが不正なJWTの場合はX-API-Keyへフォールバックし、それも無ければ401")
    void 不正なBearerトークンかつAPIキー無しは401() throws Exception {
        when(jwtDecoder.decode(anyString())).thenThrow(new JwtException("invalid signature"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sites");
        request.addHeader("Authorization", "Bearer garbage");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("invalid or missing X-API-Key");
    }

    @Test
    @DisplayName("不正なJWTでも有効なX-API-Keyが併用されていれば通す(X-API-Key経路を弱体化しない)")
    void 不正なBearerトークンでも有効なAPIキーがあれば通す() throws Exception {
        when(jwtDecoder.decode(anyString())).thenThrow(new JwtException("expired"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sites");
        request.addHeader("Authorization", "Bearer expired-jwt");
        request.addHeader("X-API-Key", VALID_API_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isNotEqualTo(401);
    }

    @Test
    @DisplayName("X-API-KeyもAuthorizationヘッダーも無ければ401(従来どおり)")
    void 何も無ければ401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sites");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("shouldNotFilter: /api/healthとPUBLIC_AUTH_PATHSは対象外(従来どおり)")
    void ヘルスチェックと公開パスは対象外() {
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/health"))).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/api/auth/login"))).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/sites"))).isFalse();
    }
}
