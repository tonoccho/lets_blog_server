package com.letsblog.analytics.service;

import com.letsblog.analytics.testsupport.AnalyticsWorld;
import com.letsblog.common.testfixtures.JwtTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CurrentActorServiceの回帰テスト(issue #1827)。操作者の解決は identity-service の
 * {@code GET /api/identity/me}(サーバー側フェイクのHTTP)に委ね、サービスと{@code IdentityClient}は実物。
 */
class CurrentActorServiceTest {

    private AnalyticsWorld world;
    private CurrentActorService service;

    @BeforeEach
    void setUp() {
        world = new AnalyticsWorld();
        service = world.currentActor;
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        world.close();
    }

    @Test
    void identity_serviceが解決した操作者のidとadmin判定を返す() {
        world.actor("t", 7L, "admin");
        world.asBearer("t");

        assertEquals(7L, service.getCurrentActorId());
        assertTrue(service.isAdmin());
    }

    @Test
    void adminでなければisAdminはfalse() {
        world.actor("t", 8L, "user");
        world.asBearer("t");

        assertEquals(8L, service.getCurrentActorId());
        assertFalse(service.isAdmin());
    }

    @Test
    void 同じリクエストの中ではidentity_serviceへの問い合わせは1回だけ() {
        world.actor("t", 7L, "admin");
        world.asBearer("t");

        service.getCurrentActorId();
        service.isAdmin();
        service.getCurrentActorId();

        assertEquals(1, world.http.requests("GET", "/api/identity/me").size());
    }

    @Test
    void 別のリクエストでは改めて問い合わせる() {
        world.actor("t", 7L, "admin");
        world.asBearer("t");
        service.getCurrentActorId();
        world.asBearer("t");
        service.getCurrentActorId();

        assertEquals(2, world.http.requests("GET", "/api/identity/me").size());
    }

    @Test
    void Authorizationヘッダーが無ければ操作者なしで問い合わせない() {
        world.asBearer(null);

        assertNull(service.getCurrentActorId());
        assertFalse(service.isAdmin());
        assertNull(service.getAuthorizationHeader());
        assertTrue(world.http.requests().isEmpty());
    }

    @Test
    void Authorizationヘッダーが空白なら操作者なしで問い合わせない() {
        world.withAuthorizationHeader("   ");

        assertNull(service.getCurrentActorId());
        assertTrue(world.http.requests().isEmpty());
    }

    @Test
    void identity_serviceが401なら操作者なし() {
        world.asBearer("rejected");

        assertNull(service.getCurrentActorId());
        assertFalse(service.isAdmin());
    }

    @Test
    void identity_serviceが5xxならIdentityServiceUnavailableExceptionで原因を伝える() {
        world.http.status("GET", "/api/identity/me", 500, "{}");
        world.asBearer("t");

        IdentityServiceUnavailableException e =
                assertThrows(IdentityServiceUnavailableException.class, () -> service.getCurrentActorId());
        assertTrue(e.getMessage().contains("/api/identity/me"));
    }

    @Test
    void getAuthorizationHeader_ヘッダーの値をそのまま返す() {
        MockHttpServletRequest request = world.asBearer("abc");

        assertEquals("Bearer abc", service.getAuthorizationHeader());
        assertEquals("Bearer abc", request.getHeader("Authorization"));
    }

    @Test
    void getCurrentActorKeycloakSub_JWTのsubをそのまま返す() {
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(JwtTestFixtures.jwt("kc-sub-1", "user")));

        assertEquals("kc-sub-1", service.getCurrentActorKeycloakSub());
    }

    @Test
    void getCurrentActorKeycloakSub_認証が無ければnull() {
        assertNull(service.getCurrentActorKeycloakSub());
    }

    @Test
    void getCurrentActorKeycloakSub_JWT以外の認証ならnull() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("u", "p", "ROLE_USER"));

        assertNull(service.getCurrentActorKeycloakSub());
    }

    @Test
    void getCurrentActorKeycloakSub_subが空白ならnull() {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwtWithoutSubject(" ")));

        assertNull(service.getCurrentActorKeycloakSub());
    }

    @Test
    void getCurrentActorKeycloakSub_subが無ければnull() {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwtWithoutSubject(null)));

        assertNull(service.getCurrentActorKeycloakSub());
    }

    private static Jwt jwtWithoutSubject(String subject) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "RS256").claim("azp", "letsblog-web")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (subject != null) {
            builder.subject(subject);
        }
        return builder.build();
    }
}
