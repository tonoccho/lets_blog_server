package com.letsblog.logwriter.service;

import com.letsblog.common.client.SyncServiceTimeoutException;
import java.util.Optional;
import com.letsblog.common.client.ActorProfile;
import com.letsblog.common.client.IdentityClient;
import com.letsblog.common.testfixtures.JwtTestFixtures;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurrentActorServiceTest {

    @Mock
    private HttpServletRequest request;

    @Mock
    private IdentityClient identityClient;

    private CurrentActorService service;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAsJwt(String subject) {
        JwtAuthenticationToken token = new JwtAuthenticationToken(JwtTestFixtures.jwt(subject));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    /** requestのAttributeをMapで模して、リクエストスコープキャッシュの挙動を再現する。 */
    private void stubRequestAttributeCache() {
        Map<String, Object> attrs = new HashMap<>();
        lenient().doAnswer(inv -> {
            attrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(request).setAttribute(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        lenient().when(request.getAttribute(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> attrs.get(inv.getArgument(0)));
    }

    @Test
    void getCurrentActorKeycloakSub_JWTが提示されていればsubを返す() {
        service = new CurrentActorService(request, identityClient);
        authenticateAsJwt("keycloak-sub-1");

        assertEquals("keycloak-sub-1", service.getCurrentActorKeycloakSub());
    }

    @Test
    void getCurrentActorKeycloakSub_JWTが無ければnullを返す() {
        service = new CurrentActorService(request, identityClient);

        assertNull(service.getCurrentActorKeycloakSub());
    }

    @Test
    void getCurrentActorId_Authorizationヘッダーが無ければidentityServiceを呼ばずnull() {
        service = new CurrentActorService(request, identityClient);
        stubRequestAttributeCache();
        when(request.getHeader("Authorization")).thenReturn(null);

        assertNull(service.getCurrentActorId());
    }

    @Test
    void getCurrentActorId_identityServiceが解決したidを返す() {
        service = new CurrentActorService(request, identityClient);
        stubRequestAttributeCache();
        when(request.getHeader("Authorization")).thenReturn("Bearer valid-jwt");
        when(identityClient.lookupProfile("Bearer valid-jwt"))
                .thenReturn(Optional.of(new ActorProfile(42L, "editor")));

        assertEquals(42L, service.getCurrentActorId());
        // リクエストスコープでキャッシュされ、2回目の呼び出しではidentityClientを再度呼ばない
        service.isAdmin();
        verify(identityClient, times(1)).lookupProfile(eq("Bearer valid-jwt"));
    }

    @Test
    void getCurrentActorId_identityService呼び出し失敗は例外を伝播させる() {
        service = new CurrentActorService(request, identityClient);
        stubRequestAttributeCache();
        when(request.getHeader("Authorization")).thenReturn("Bearer valid-jwt");
        when(identityClient.lookupProfile("Bearer valid-jwt"))
                .thenThrow(new SyncServiceTimeoutException("identity-service", "http://identity:8080", "GET /api/identity/me", null));

        assertThrows(IdentityServiceUnavailableException.class, () -> service.getCurrentActorId());
    }

    @Test
    void tryGetCurrentActorId_identityService呼び出し失敗時はnullへ握りつぶす() {
        service = new CurrentActorService(request, identityClient);
        stubRequestAttributeCache();
        when(request.getHeader("Authorization")).thenReturn("Bearer valid-jwt");
        when(identityClient.lookupProfile("Bearer valid-jwt"))
                .thenThrow(new SyncServiceTimeoutException("identity-service", "http://identity:8080", "GET /api/identity/me", null));

        assertNull(service.tryGetCurrentActorId());
    }

    @Test
    void isAdmin_roleがadminならtrue() {
        service = new CurrentActorService(request, identityClient);
        stubRequestAttributeCache();
        when(request.getHeader("Authorization")).thenReturn("Bearer valid-jwt");
        when(identityClient.lookupProfile("Bearer valid-jwt"))
                .thenReturn(Optional.of(new ActorProfile(1L, "admin")));

        assertEquals(true, service.isAdmin());
    }

    @Test
    void isAdmin_未認証ならfalse() {
        service = new CurrentActorService(request, identityClient);
        stubRequestAttributeCache();
        when(request.getHeader("Authorization")).thenReturn(null);

        assertFalse(service.isAdmin());
    }

    // ---- issue #906 / #829: 401/403 は「操作者なし」で、障害と区別する ----

    @Test
    void getCurrentActorId_無効化ユーザー等でidentityが401_403ならnull() {
        // IdentityClient#lookupProfile が 401/403 を Optional.empty() へ畳む(issue #829)。
        // 障害(例外)と違い、ここは「操作者を解決できなかった」として静かにnullを返す。
        service = new CurrentActorService(request, identityClient);
        stubRequestAttributeCache();
        when(request.getHeader("Authorization")).thenReturn("Bearer revoked-jwt");
        when(identityClient.lookupProfile("Bearer revoked-jwt")).thenReturn(Optional.empty());

        assertNull(service.getCurrentActorId());
    }

    @Test
    void isAdmin_identityが401_403ならfalse() {
        service = new CurrentActorService(request, identityClient);
        stubRequestAttributeCache();
        when(request.getHeader("Authorization")).thenReturn("Bearer revoked-jwt");
        when(identityClient.lookupProfile("Bearer revoked-jwt")).thenReturn(Optional.empty());

        assertFalse(service.isAdmin());
    }

    @Test
    void isAdmin_identityService障害はfalseへ縮退させず例外を伝播させる() {
        // 障害を静かにadmin権限無しへ縮退させると、権限チェックが素通りする方向の不具合を生みうる。
        service = new CurrentActorService(request, identityClient);
        stubRequestAttributeCache();
        when(request.getHeader("Authorization")).thenReturn("Bearer valid-jwt");
        when(identityClient.lookupProfile("Bearer valid-jwt")).thenThrow(
                new SyncServiceTimeoutException("identity-service", "http://identity:8080",
                        "GET /api/identity/me", null));

        assertThrows(IdentityServiceUnavailableException.class, () -> service.isAdmin());
    }
}
