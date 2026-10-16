package com.letsblog.logwriter.service;

import com.letsblog.logwriter.testsupport.FakeHttpServer;
import com.letsblog.logwriter.testsupport.LogWriterIntegrationTestBase;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CurrentActorService(issue #1829で書き直し)。IdentityClient・HttpServletRequest・CurrentActorService は実物で、
 * identity-service は HTTP のサーバー側フェイク({@link FakeHttpServer})が答える。
 */
class CurrentActorServiceTest extends LogWriterIntegrationTestBase {

    @Autowired
    private CurrentActorService service;

    private Jwt jwtWithClaims(String name, Object value) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "RS256").claim("azp", "letsblog-web")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        return (name == null ? builder.claim("other", "x") : builder.claim(name, value)).build();
    }

    private int identityCalls() {
        return HTTP.requests("GET", "/api/identity/me").size();
    }

    // ---- getCurrentActorKeycloakSub ----

    @Test
    void getCurrentActorKeycloakSub_JWTが提示されていればsubを返す() {
        authenticateAs("keycloak-sub-1");

        assertEquals("keycloak-sub-1", service.getCurrentActorKeycloakSub());
    }

    @Test
    void getCurrentActorKeycloakSub_JWTが無ければnullを返す() {
        assertNull(service.getCurrentActorKeycloakSub());
    }

    @Test
    void getCurrentActorKeycloakSub_JWT以外の認証ならnullを返す() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user", "password"));

        assertNull(service.getCurrentActorKeycloakSub());
    }

    @Test
    void getCurrentActorKeycloakSub_subが空白ならnullを返す() {
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwtWithClaims("sub", "   ")));

        assertNull(service.getCurrentActorKeycloakSub());
    }

    @Test
    void getCurrentActorKeycloakSub_subクレームが無ければnullを返す() {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwtWithClaims(null, null)));

        assertNull(service.getCurrentActorKeycloakSub());
    }

    // ---- getCurrentActorId / isAdmin ----

    @Test
    void getCurrentActorId_Authorizationヘッダーが無ければidentityServiceを呼ばずnull() {
        asBearer(null);

        assertNull(service.getCurrentActorId());
        assertEquals(0, identityCalls());
    }

    @Test
    void getCurrentActorId_Authorizationヘッダーが空白ならidentityServiceを呼ばずnull() {
        withAuthorizationHeader("   ");

        assertNull(service.getCurrentActorId());
        assertEquals(0, identityCalls());
    }

    @Test
    void getCurrentActorId_identityServiceが解決したidを返し_呼び出し元のトークンを転送する() {
        actor("valid-jwt", 42L, "editor");
        asBearer("valid-jwt");

        assertEquals(42L, service.getCurrentActorId());

        assertEquals("Bearer valid-jwt", HTTP.last().header("Authorization"));
    }

    @Test
    void getCurrentActorId_同じリクエストの中ではidentityServiceへ1回しか問い合わせない() {
        actor("valid-jwt", 42L, "editor");
        asBearer("valid-jwt");

        assertEquals(42L, service.getCurrentActorId());
        assertFalse(service.isAdmin());
        assertEquals(42L, service.tryGetCurrentActorId());

        assertEquals(1, identityCalls());
    }

    @Test
    void getCurrentActorId_identityService呼び出し失敗は例外を伝播させる() {
        // identity-service が 404(401/403 以外の 4xx)を返す = 障害として扱う。
        HTTP.status("GET", "/api/identity/me", 404, "{}");
        asBearer("valid-jwt");

        assertThrows(IdentityServiceUnavailableException.class, () -> service.getCurrentActorId());
    }

    @Test
    void tryGetCurrentActorId_identityService呼び出し失敗時はnullへ握りつぶす() {
        HTTP.status("GET", "/api/identity/me", 404, "{}");
        asBearer("valid-jwt");

        assertNull(service.tryGetCurrentActorId());
    }

    @Test
    void tryGetCurrentActorId_解決できればidを返す() {
        actor("valid-jwt", 7L, "viewer");
        asBearer("valid-jwt");

        assertEquals(7L, service.tryGetCurrentActorId());
    }

    @Test
    void isAdmin_roleがadminならtrue() {
        actor("valid-jwt", 1L, "admin");
        asBearer("valid-jwt");

        assertTrue(service.isAdmin());
    }

    @Test
    void isAdmin_未認証ならfalse() {
        asBearer(null);

        assertFalse(service.isAdmin());
    }

    // ---- issue #906 / #829: 401/403 は「操作者なし」で、障害と区別する ----

    @Test
    void getCurrentActorId_無効化ユーザー等でidentityが401ならnull() {
        // 未登録のトークンは identity-service が 401 を返す。
        asBearer("revoked-jwt");

        assertNull(service.getCurrentActorId());
        assertEquals(1, identityCalls());
    }

    @Test
    void isAdmin_identityが403ならfalse() {
        HTTP.status("GET", "/api/identity/me", 403, "{}");
        asBearer("revoked-jwt");

        assertFalse(service.isAdmin());
    }

    @Test
    void isAdmin_identityService障害はfalseへ縮退させず例外を伝播させる() {
        // 障害を静かにadmin権限無しへ縮退させると、権限チェックが素通りする方向の不具合を生みうる。
        HTTP.status("GET", "/api/identity/me", 404, "{}");
        asBearer("valid-jwt");

        assertThrows(IdentityServiceUnavailableException.class, () -> service.isAdmin());
    }

    // ---- リクエストの情報 ----

    @Test
    void getRemoteIp_X_Forwarded_Forがあればそれを返す() {
        asBearer(null).addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");

        assertEquals("203.0.113.9, 10.0.0.1", service.getRemoteIp());
    }

    @Test
    void getRemoteIp_X_Forwarded_Forが空白ならremoteAddrを返す() {
        MockHttpServletRequest request = asBearer(null);
        request.addHeader("X-Forwarded-For", "  ");
        request.setRemoteAddr("198.51.100.4");

        assertEquals("198.51.100.4", service.getRemoteIp());
    }

    @Test
    void getRemoteIp_X_Forwarded_Forが無ければremoteAddrを返す() {
        asBearer(null).setRemoteAddr("198.51.100.5");

        assertEquals("198.51.100.5", service.getRemoteIp());
    }

    @Test
    void getUserAgentとgetAuthorizationHeaderはリクエストのヘッダーを返す() {
        MockHttpServletRequest request = asBearer("tok");
        request.addHeader("User-Agent", "agent/1.0");

        assertEquals("agent/1.0", service.getUserAgent());
        assertEquals("Bearer tok", service.getAuthorizationHeader());
    }

    @Test
    void getAuthorizationHeader_ヘッダーが無ければnull() {
        asBearer(null);

        assertNull(service.getAuthorizationHeader());
    }
}
