package com.letsblog.publishing.service;

import com.letsblog.publishing.testsupport.PublishingWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * CurrentActorServiceの単体テスト(issue #1815)。操作者の解決は、JWT(Spring SecurityのSecurityContext)と
 * HTTPリクエスト(Spring Testの{@code MockHttpServletRequest})から行い、identity-serviceへの問い合わせだけを
 * {@code FakeHttpServer}で差し替える(自前のクラスはモックしない)。
 */
class CurrentActorServiceTest {

    private PublishingWorld world;
    private CurrentActorService service;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld();
        service = world.currentActorService;
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        world.close();
    }

    private static JwtAuthenticationToken jwtWithSubject(String subject) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).claim("scope", "x");
        if (subject != null) {
            builder.subject(subject);
        }
        return new JwtAuthenticationToken(builder.build());
    }

    // ---- getCurrentActorKeycloakSub ----

    @Test
    void getCurrentActorKeycloakSub_JWTのsubをそのまま返す() {
        SecurityContextHolder.getContext().setAuthentication(jwtWithSubject("kc-sub-1"));

        assertEquals("kc-sub-1", service.getCurrentActorKeycloakSub());
    }

    @Test
    void getCurrentActorKeycloakSub_JWTでない認証ならnull() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("user", "pw"));

        assertNull(service.getCurrentActorKeycloakSub());
    }

    @Test
    void getCurrentActorKeycloakSub_認証が無ければnull() {
        assertNull(service.getCurrentActorKeycloakSub());
    }

    @Test
    void getCurrentActorKeycloakSub_subが無いか空白ならnull() {
        SecurityContextHolder.getContext().setAuthentication(jwtWithSubject(null));
        assertNull(service.getCurrentActorKeycloakSub());

        SecurityContextHolder.getContext().setAuthentication(jwtWithSubject("  "));
        assertNull(service.getCurrentActorKeycloakSub());
    }

    // ---- identity-serviceでの操作者の解決 ----

    @Test
    void getCurrentActorId_identity_serviceが解決したユーザーIDを返し_同じリクエスト内では1回しか問い合わせない() {
        world.stubActor("Bearer t", 10L, "user", "u@example.com").asUser("Bearer t");

        assertEquals(10L, service.getCurrentActorId());
        assertEquals("u@example.com", service.getCurrentActorEmail());
        assertFalse(service.isAdmin());

        assertEquals(1, world.identity.requests("GET", "/api/identity/me").size());
    }

    @Test
    void isAdmin_roleがadminならtrue() {
        world.stubActor("Bearer t", 1L, "admin", "a@example.com").asUser("Bearer t");

        assertTrue(service.isAdmin());
    }

    @Test
    void Authorizationヘッダーが無ければ操作者なし_identity_serviceへ問い合わせない() {
        world.asUser(null);

        assertNull(service.getCurrentActorId());
        assertNull(service.getCurrentActorEmail());
        assertFalse(service.isAdmin());
        assertTrue(world.identity.requests().isEmpty());
    }

    @Test
    void Authorizationヘッダーが空白でも操作者なし() {
        world.asUser("   ");

        assertNull(service.getCurrentActorId());
        assertTrue(world.identity.requests().isEmpty());
    }

    @Test
    void identity_serviceが401を返す無効なトークンは操作者なし() {
        world.asUser("Bearer expired");

        assertNull(service.getCurrentActorId());
        assertFalse(service.isAdmin());
    }

    @Test
    void identity_serviceの障害は操作者なしに縮退せず例外を伝える() {
        world.asUser("Bearer t");
        world.identity.status("GET", "/api/identity/me", 503, "{\"error\":\"down\"}");

        assertThrows(IdentityServiceUnavailableException.class, () -> service.getCurrentActorId());
    }

    // ---- リクエストの情報 ----

    @Test
    void getAuthorizationHeader_リクエストのAuthorizationをそのまま返す() {
        world.asUser("Bearer abc");

        assertEquals("Bearer abc", service.getAuthorizationHeader());
    }

    @Test
    void getAuthorizationHeader_ヘッダーが無ければnull() {
        world.asUser(null);

        assertNull(service.getAuthorizationHeader());
    }

    @Test
    void getRemoteIp_X_Forwarded_Forがあればそれを優先する() {
        world.request.addHeader("X-Forwarded-For", "203.0.113.9");
        world.request.setRemoteAddr("10.0.0.1");

        assertEquals("203.0.113.9", service.getRemoteIp());
    }

    @Test
    void getRemoteIp_X_Forwarded_Forが無いか空白ならリモートアドレス() {
        world.request.setRemoteAddr("10.0.0.1");
        assertEquals("10.0.0.1", service.getRemoteIp());

        world.request.addHeader("X-Forwarded-For", "   ");
        assertEquals("10.0.0.1", service.getRemoteIp());
    }

    @Test
    void getUserAgent_リクエストのUser_Agentを返す() {
        world.request.addHeader("User-Agent", "JUnit");

        assertEquals("JUnit", service.getUserAgent());
    }
}
