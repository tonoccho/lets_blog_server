package com.letsblog.ai.service;

import com.letsblog.ai.testsupport.AiWorld;
import com.letsblog.ai.testsupport.FakeHttpServer;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CurrentActorService(issue #1823)。操作者のID・admin判定はidentity-service(HTTPフェイク)の{@code GET /api/identity/me}を
 * 実物の{@code IdentityClient}で呼んで解決し、リクエスト単位でキャッシュする。JWTのsub・Authorizationヘッダーは
 * SecurityContextとMockHttpServletRequest(いずれもSpringが提供する実装)で与える。
 */
class CurrentActorServiceTest {

    private static final String ME = "/api/identity/me";

    private AiWorld world;
    private CurrentActorService service;

    @BeforeEach
    void setUp() {
        world = new AiWorld();
        service = world.currentActorService;
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        world.close();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return new Jwt("token", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"), claims);
    }

    private void authenticateAs(String subject) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                subject == null ? jwt(Map.of("scope", "x")) : jwt(Map.of("sub", subject))));
    }

    // ---- getCurrentActorKeycloakSub ----

    @Test
    void sub_JWTが無ければnull() {
        assertNull(service.getCurrentActorKeycloakSub());
    }

    @Test
    void sub_JWT以外の認証ならnull() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("u", "p"));

        assertNull(service.getCurrentActorKeycloakSub());
    }

    @Test
    void sub_JWTのsubをそのまま返しidentity_serviceへは問い合わせない() {
        authenticateAs("kc-123");

        assertEquals("kc-123", service.getCurrentActorKeycloakSub());
        assertEquals(0, world.http.requests().size());
    }

    @Test
    void sub_subが空白またはクレームに無ければnull() {
        authenticateAs("   ");
        assertNull(service.getCurrentActorKeycloakSub());

        authenticateAs(null);
        assertNull(service.getCurrentActorKeycloakSub());
    }

    // ---- getAuthorizationHeader ----

    @Test
    void Authorizationヘッダーをそのまま返す_無ければnull() {
        assertNull(service.getAuthorizationHeader());

        world.request.addHeader("Authorization", "Bearer abc");
        assertEquals("Bearer abc", service.getAuthorizationHeader());
    }

    // ---- getCurrentActorId / isAdmin ----

    @Test
    void ID_identity_serviceが解決したユーザーのIDを返しBearerトークンを転送する() {
        world.actor(42L, "user");

        assertEquals(42L, service.getCurrentActorId());
        assertEquals(AiWorld.BEARER, world.http.requests("GET", ME).get(0).header("Authorization"));
    }

    @Test
    void ID_Authorizationヘッダーが無ければ問い合わせずnullとfalse() {
        assertNull(service.getCurrentActorId());
        assertFalse(service.isAdmin());
        assertEquals(0, world.http.requests().size());
    }

    @Test
    void ID_Authorizationヘッダーが空白だけなら問い合わせずnull() {
        world.request.addHeader("Authorization", "   ");

        assertNull(service.getCurrentActorId());
        assertEquals(0, world.http.requests().size());
    }

    @Test
    void isAdmin_ロールがadminならtrue_それ以外はfalse() {
        world.admin();
        assertTrue(service.isAdmin());

        // 同じリクエストでは結果をキャッシュするので、別のリクエストで確かめる。
        try (AiWorld other = new AiWorld().member(7L)) {
            assertFalse(other.currentActorService.isAdmin());
            assertEquals(7L, other.currentActorService.getCurrentActorId());
        }
    }

    @Test
    void 解決結果は同じリクエストの間キャッシュされ問い合わせは1回だけ() {
        world.actor(42L, "user");

        service.getCurrentActorId();
        service.getCurrentActorId();
        service.isAdmin();

        assertEquals(1, world.http.requests("GET", ME).size());
    }

    @Test
    void 操作者なしの結果もキャッシュされる() {
        world.request.addHeader("Authorization", AiWorld.BEARER);
        world.http.on("GET", ME, FakeHttpServer.Response.json(403, "{}"));

        assertNull(service.getCurrentActorId());
        assertFalse(service.isAdmin());

        assertEquals(1, world.http.requests("GET", ME).size());
    }

    @Test
    void identity_serviceが401や403なら操作者なしでnull_障害とは区別する() {
        world.request.addHeader("Authorization", AiWorld.BEARER);
        world.http.on("GET", ME, FakeHttpServer.Response.json(401, "{}"));

        assertNull(service.getCurrentActorId());
    }

    @Test
    void identity_serviceが5xxなら例外にして伝播させる_権限を黙って落とさない() {
        world.request.addHeader("Authorization", AiWorld.BEARER);
        world.http.on("GET", ME, FakeHttpServer.Response.json(500, "{}"));

        IdentityServiceUnavailableException e =
                assertThrows(IdentityServiceUnavailableException.class, () -> service.getCurrentActorId());

        assertTrue(e.getMessage().contains("/api/identity/me"), e.getMessage());
    }
}
