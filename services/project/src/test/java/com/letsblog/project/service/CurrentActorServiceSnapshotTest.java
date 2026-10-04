package com.letsblog.project.service;

import com.letsblog.project.client.ActorProfile;
import com.letsblog.project.client.IdentityClient;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 操作者の引き継ぎ(issue #1479)。{@code @Async}のスレッドには{@code SecurityContextHolder}も
 * リクエストも無いため、リクエストの中で{@link ActorSnapshot}を取り、ジョブのスレッドで
 * {@code runAs}して、監査ログ(AuditLogAspect)とサイト登録の著者解決が従来どおり操作者を参照できるようにする。
 */
@DisplayName("CurrentActorService: 操作者の引き継ぎ(issue #1479)")
class CurrentActorServiceSnapshotTest {

    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final IdentityClient identityClient = mock(IdentityClient.class);
    private final CurrentActorService service = new CurrentActorService(request, identityClient);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void signIn(String subject) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(subject).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test
    @DisplayName("snapshot はリクエストの操作者・IP・UA・Authorization を写し取る")
    void snapshotCapturesRequestActor() {
        signIn("sub-1");
        request.addHeader("Authorization", "Bearer abc");
        request.addHeader("X-Forwarded-For", "203.0.113.9");
        request.addHeader("User-Agent", "at-agent");
        when(identityClient.lookupProfile("Bearer abc"))
                .thenReturn(Optional.of(new ActorProfile(5L, "admin", "a@example.com")));

        ActorSnapshot snapshot = service.snapshot();

        assertEquals(new ActorSnapshot(5L, "sub-1", "a@example.com", "203.0.113.9", "at-agent", "Bearer abc", true),
                snapshot);
    }

    @Test
    @DisplayName("操作者が解決できないリクエストの snapshot は空の値を持つ")
    void snapshotWithoutActor() {
        ActorSnapshot snapshot = service.snapshot();

        assertNull(snapshot.userId());
        assertNull(snapshot.keycloakSub());
        assertNull(snapshot.authorization());
        assertFalse(snapshot.admin());
    }

    @Test
    @DisplayName("runAs の中では snapshot の値を返し、リクエストにもセキュリティコンテキストにも触れない")
    void runAsServesSnapshotWithoutRequest() {
        HttpServletRequestThatMustNotBeUsed forbidden = new HttpServletRequestThatMustNotBeUsed();
        CurrentActorService bound = new CurrentActorService(forbidden, identityClient);
        ActorSnapshot snapshot = new ActorSnapshot(9L, "sub-9", "n@example.com", "198.51.100.1", "ua", "Bearer z", true);

        String result = bound.runAs(snapshot, () -> {
            assertEquals(9L, bound.getCurrentActorId());
            assertEquals("sub-9", bound.getCurrentActorKeycloakSub());
            assertEquals("n@example.com", bound.getCurrentActorEmail());
            assertEquals("198.51.100.1", bound.getRemoteIp());
            assertEquals("ua", bound.getUserAgent());
            assertEquals("Bearer z", bound.getAuthorizationHeader());
            assertTrue(bound.isAdmin());
            return "ok";
        });

        assertEquals("ok", result);
    }

    @Test
    @DisplayName("runAs は終わったら(例外でも)束縛を外し、以降はリクエストを参照する")
    void runAsUnbindsAfterwards() {
        request.addHeader("User-Agent", "request-ua");
        ActorSnapshot snapshot = new ActorSnapshot(9L, "s", "e", "ip", "snapshot-ua", "Bearer z", false);

        assertThrows(IllegalStateException.class, () -> service.runAs(snapshot, () -> {
            throw new IllegalStateException("boom");
        }));

        assertEquals("request-ua", service.getUserAgent());
    }

    /** どのメソッドを呼んでも失敗する(No thread-bound request を模す)リクエスト。 */
    private static final class HttpServletRequestThatMustNotBeUsed extends MockHttpServletRequest {
        @Override
        public String getHeader(String name) {
            throw new IllegalStateException("No thread-bound request found");
        }

        @Override
        public String getRemoteAddr() {
            throw new IllegalStateException("No thread-bound request found");
        }

        @Override
        public Object getAttribute(String name) {
            throw new IllegalStateException("No thread-bound request found");
        }
    }
}
