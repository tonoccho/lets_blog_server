package com.letsblog.content.service;

import com.letsblog.content.testsupport.ContentWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CurrentActorService(issue #1821)。identity-serviceは{@link ContentWorld}のHTTPフェイクで、
 * IdentityClient・CurrentActorServiceは実物。
 */
class CurrentActorServiceTest {

    private ContentWorld world;

    @BeforeEach
    void setUp() {
        world = new ContentWorld();
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private CurrentActorService service() {
        return world.currentActorService;
    }

    @Test
    void JWTが無ければsubはnull() {
        assertNull(service().getCurrentActorKeycloakSub());
    }

    @Test
    void JWT以外の認証ならsubはnull() {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.TestingAuthenticationToken("u", "p"));

        assertNull(service().getCurrentActorKeycloakSub());
    }

    @Test
    void JWTのsubを返す() {
        world.signedInAs("kc-sub-1");

        assertEquals("kc-sub-1", service().getCurrentActorKeycloakSub());
    }

    @Test
    void JWTのsubが空白ならnull() {
        world.signedInAs("  ");

        assertNull(service().getCurrentActorKeycloakSub());
    }

    @Test
    void JWTにsubが無ければnull() {
        org.springframework.security.oauth2.jwt.Jwt jwt = org.springframework.security.oauth2.jwt.Jwt
                .withTokenValue("t").header("alg", "none").claim("scope", "x").build();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt));

        assertNull(service().getCurrentActorKeycloakSub());
    }

    @Test
    void identity_serviceが解決した操作者のIDとadmin判定を返す() {
        world.actingAsAdmin(42L);

        assertEquals(42L, service().getCurrentActorId());
        assertTrue(service().isAdmin());
    }

    @Test
    void adminでなければfalse() {
        world.actingAsUser(42L);

        assertFalse(service().isAdmin());
    }

    @Test
    void 解決結果は1リクエストにつき1回だけ問い合わせる() {
        world.actingAsAdmin(42L);

        service().getCurrentActorId();
        service().isAdmin();
        service().getCurrentActorId();

        assertEquals(1, world.http.requests("GET", "/api/identity/me").size());
    }

    @Test
    void Authorizationヘッダーが無ければ操作者なしでidentity_serviceへ問い合わせない() {
        assertNull(service().getCurrentActorId());
        assertFalse(service().isAdmin());
        assertTrue(world.http.requests("GET", "/api/identity/me").isEmpty());
    }

    @Test
    void Authorizationヘッダーが空白なら操作者なし() {
        world.request.addHeader("Authorization", "  ");

        assertNull(service().getCurrentActorId());
        assertTrue(world.http.requests("GET", "/api/identity/me").isEmpty());
    }

    @Test
    void identity_serviceが401を返すトークンは操作者なし() {
        world.request.addHeader("Authorization", "Bearer unknown");

        assertNull(service().getCurrentActorId());
        assertFalse(service().isAdmin());
    }

    @Test
    void identity_serviceの障害は操作者なしにせず例外として伝える() {
        world.actingAsAdmin(42L).identityServiceDown();

        assertThrows(IdentityServiceUnavailableException.class, () -> service().isAdmin());
    }

    @Test
    void Authorizationヘッダーをそのまま返す() {
        world.request.addHeader("Authorization", "Bearer abc");

        assertEquals("Bearer abc", service().getAuthorizationHeader());
    }

    @Test
    void X_Forwarded_Forがあればそれをリモートアドレスとして返す() {
        world.request.addHeader("X-Forwarded-For", "203.0.113.5");
        world.request.setRemoteAddr("10.0.0.1");

        assertEquals("203.0.113.5", service().getRemoteIp());
    }

    @Test
    void X_Forwarded_Forが無ければ接続元のアドレスを返す() {
        world.request.setRemoteAddr("10.0.0.1");

        assertEquals("10.0.0.1", service().getRemoteIp());
    }

    @Test
    void X_Forwarded_Forが空白なら接続元のアドレスを返す() {
        world.request.addHeader("X-Forwarded-For", " ");
        world.request.setRemoteAddr("10.0.0.1");

        assertEquals("10.0.0.1", service().getRemoteIp());
    }

    @Test
    void User_Agentを返す() {
        world.request.addHeader("User-Agent", "JUnit/5");

        assertEquals("JUnit/5", service().getUserAgent());
    }
}
