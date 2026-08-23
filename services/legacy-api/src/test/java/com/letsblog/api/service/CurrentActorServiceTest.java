package com.letsblog.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.letsblog.api.domain.User;
import com.letsblog.api.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class CurrentActorServiceTest {

    @Mock
    private HttpServletRequest request;

    @Mock
    private UserRepository userRepository;

    private CurrentActorService service;

    // request.setAttribute/getAttributeの簡易スタブ(モックのマップに退避する)。
    private final Map<String, Object> requestAttributes = new HashMap<>();

    @BeforeEach
    void setUp() {
        lenient().doAnswer(invocation -> {
            requestAttributes.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(request).setAttribute(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        lenient()
                .when(request.getAttribute(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> requestAttributes.get(invocation.getArgument(0, String.class)));

        service = new CurrentActorService(request, userRepository);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void JWTが無い場合はX_Actor_Idヘッダーへフォールバックする() {
        when(request.getHeader("X-Actor-Id")).thenReturn("42");
        when(request.getHeader("X-Actor-Role")).thenReturn("admin");

        assertThat(service.getCurrentActorId()).isEqualTo(42L);
        assertThat(service.getCurrentActorRole()).isEqualTo("admin");
        assertThat(service.isAdmin()).isTrue();
    }

    @Test
    void 有効なJWTがありローカルUserが見つかる場合はJWTを優先する() {
        User user = new User();
        user.setId(99L);
        user.setRole("admin");
        when(userRepository.findByKeycloakSub("keycloak-sub-1")).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(jwtAuthenticationToken("keycloak-sub-1"));

        assertThat(service.getCurrentActorId()).isEqualTo(99L);
        assertThat(service.getCurrentActorRole()).isEqualTo("admin");
        // JWTが優先され、ヘッダーは一切参照されないことを確認。
        org.mockito.Mockito.verify(request, org.mockito.Mockito.never()).getHeader("X-Actor-Id");
        org.mockito.Mockito.verify(request, org.mockito.Mockito.never()).getHeader("X-Actor-Role");
    }

    @Test
    void JWTはあるが対応するローカルUserが無い場合はヘッダーへフォールバックしない() {
        when(userRepository.findByKeycloakSub("unknown-sub")).thenReturn(Optional.empty());
        SecurityContextHolder.getContext().setAuthentication(jwtAuthenticationToken("unknown-sub"));

        assertThat(service.getCurrentActorId()).isNull();
        assertThat(service.getCurrentActorRole()).isNull();
        assertThat(service.isAdmin()).isFalse();
        // ローカルUserが無くてもヘッダーへはフォールバックしない(JWT提示者へのなりすまし防止)。
        org.mockito.Mockito.verify(request, org.mockito.Mockito.never()).getHeader("X-Actor-Id");
        org.mockito.Mockito.verify(request, org.mockito.Mockito.never()).getHeader("X-Actor-Role");
    }

    @Test
    void JWTにsubクレームが無い場合はkeycloak_sub未設定のユーザーへ誤って解決しない() {
        // 実機検証(#563)で発見した回帰: subが無いJWTをそのままfindByKeycloakSub(null)へ渡すと、
        // Spring Data JPAの派生クエリはnullパラメータを"IS NULL"として扱うため、
        // keycloak_subが未移行(NULL)のローカルユーザーへ誤って解決されてしまう
        // (この環境では、それが唯一の実運用アカウントだった)。
        SecurityContextHolder.getContext().setAuthentication(jwtAuthenticationTokenWithoutSubject());

        assertThat(service.getCurrentActorId()).isNull();
        assertThat(service.getCurrentActorRole()).isNull();
        assertThat(service.isAdmin()).isFalse();
        org.mockito.Mockito.verify(userRepository, org.mockito.Mockito.never()).findByKeycloakSub(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void JWT解決結果は1リクエストにつき1回だけDBへ問い合わせる() {
        User user = new User();
        user.setId(1L);
        user.setRole("user");
        when(userRepository.findByKeycloakSub("keycloak-sub-1")).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(jwtAuthenticationToken("keycloak-sub-1"));

        service.getCurrentActorId();
        service.getCurrentActorRole();
        service.isAdmin();

        org.mockito.Mockito.verify(userRepository, org.mockito.Mockito.times(1)).findByKeycloakSub("keycloak-sub-1");
    }

    private JwtAuthenticationToken jwtAuthenticationToken(String subject) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("sub", subject)
                .issuedAt(java.time.Instant.now())
                .expiresAt(java.time.Instant.now().plusSeconds(60))
                .build();
        return new JwtAuthenticationToken(jwt);
    }

    /** 実際にKeycloakのクライアント設定次第でsubクレームが欠落したトークンが返るケースを再現する。 */
    private JwtAuthenticationToken jwtAuthenticationTokenWithoutSubject() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("azp", "admin-cli")
                .issuedAt(java.time.Instant.now())
                .expiresAt(java.time.Instant.now().plusSeconds(60))
                .build();
        return new JwtAuthenticationToken(jwt);
    }
}
