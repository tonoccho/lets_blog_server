package com.letsblog.media.service;

import com.letsblog.media.testsupport.MediaWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CurrentActorServiceの分岐(issue #1819)。IdentityClient は実物で、identity-service(/api/identity/me)は
 * フェイクHTTPサーバーが演じる。
 */
@DisplayName("CurrentActorService: リクエストからの操作者の解決")
class CurrentActorServiceTest {

    private final MediaWorld world = new MediaWorld();
    private final CurrentActorService service = world.currentActorService;

    @AfterEach
    void close() {
        world.close();
    }

    private static void signInWith(Jwt.Builder builder) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(builder.build()));
    }

    @Test
    @DisplayName("JWT が無ければ sub は null")
    void noJwt() {
        assertNull(service.getCurrentActorKeycloakSub());
    }

    @Test
    @DisplayName("JWT に sub が無い・空白なら null、あればそのまま返す")
    void subjectVariants() {
        signInWith(Jwt.withTokenValue("t").header("alg", "none").claim("scope", "a"));
        assertNull(service.getCurrentActorKeycloakSub());
        signInWith(Jwt.withTokenValue("t").header("alg", "none").subject("  "));
        assertNull(service.getCurrentActorKeycloakSub());
        signInWith(Jwt.withTokenValue("t").header("alg", "none").subject("sub-1"));
        assertEquals("sub-1", service.getCurrentActorKeycloakSub());
    }

    @Test
    @DisplayName("X-Forwarded-For があればそれを優先し、空白や無ければ接続元のアドレスを返す")
    void remoteIp() {
        world.request.setRemoteAddr("198.51.100.7");
        assertEquals("198.51.100.7", service.getRemoteIp());
        world.request.addHeader("X-Forwarded-For", "  ");
        assertEquals("198.51.100.7", service.getRemoteIp());
        world.request.removeHeader("X-Forwarded-For");
        world.request.addHeader("X-Forwarded-For", "203.0.113.9");
        assertEquals("203.0.113.9", service.getRemoteIp());
    }

    @Test
    @DisplayName("User-Agent ヘッダーをそのまま返す")
    void userAgent() {
        assertNull(service.getUserAgent());
        world.request.addHeader("User-Agent", "curl/8");
        assertEquals("curl/8", service.getUserAgent());
    }

    @Test
    @DisplayName("Authorization が無ければ identity-service に問い合わせず、操作者なし(非 admin)にする")
    void noAuthorization() {
        assertNull(service.getCurrentActorId());
        assertFalse(service.isAdmin());
        assertFalse(service.isAuthenticated());
        assertTrue(world.http.requests("GET", "/api/identity/me").isEmpty());
    }

    @Test
    @DisplayName("Authorization が空白だけでも identity-service に問い合わせず、操作者なしにする")
    void blankAuthorization() {
        world.request.addHeader("Authorization", "   ");

        assertNull(service.getCurrentActorId());
        assertTrue(world.http.requests("GET", "/api/identity/me").isEmpty());
    }

    @Test
    @DisplayName("identity-service が 401(トークン不正・無効化ユーザー)なら操作者なし")
    void unknownToken() {
        world.request.addHeader("Authorization", "Bearer unknown");

        assertNull(service.getCurrentActorId());
        assertNull(service.tryGetCurrentActorId());
    }

    @Test
    @DisplayName("identity-service の障害(5xx)は例外になり、tryGet は null に丸める")
    void identityDown() {
        world.identityServiceDown();
        world.request.addHeader("Authorization", "Bearer x");

        assertThrows(IdentityServiceUnavailableException.class, () -> service.getCurrentActorId());
        assertNull(service.tryGetCurrentActorId());
    }

    @Test
    @DisplayName("プロフィールは1リクエストにつき1回だけ問い合わせて使い回す")
    void profileIsCachedPerRequest() {
        world.actingAs(5L, "admin");

        assertEquals(5L, service.getCurrentActorId());
        assertEquals(5L, service.tryGetCurrentActorId());
        assertTrue(service.isAdmin());
        assertTrue(service.isAuthenticated());
        assertEquals("Bearer token-5", service.getAuthorizationHeader());
        assertEquals(1, world.http.requests("GET", "/api/identity/me").size());
    }

    @Test
    @DisplayName("admin 以外のロールは isAdmin が false")
    void nonAdminRole() {
        world.actingAsUser(7L);

        assertFalse(service.isAdmin());
        assertTrue(service.isAuthenticated());
    }
}
