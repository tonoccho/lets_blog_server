package com.letsblog.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.identity.domain.User;
import com.letsblog.identity.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
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
    void JWTが無い場合は操作者なしを返す() {
        assertThat(service.getCurrentActorId()).isNull();
        assertThat(service.getCurrentActorRole()).isNull();
        assertThat(service.isAdmin()).isFalse();
    }

    @Test
    void 有効なJWTがありローカルUserが見つかる場合はそのUserを返す() {
        User user = new User();
        user.setId(99L);
        user.setRole("admin");
        when(userRepository.findByKeycloakSub("keycloak-sub-1")).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(jwtAuthenticationToken("keycloak-sub-1"));

        assertThat(service.getCurrentActorId()).isEqualTo(99L);
        assertThat(service.getCurrentActorRole()).isEqualTo("admin");
    }

    @Test
    void JWTはあるが対応するローカルUserが無い場合は操作者なしを返す() {
        when(userRepository.findByKeycloakSub("unknown-sub")).thenReturn(Optional.empty());
        SecurityContextHolder.getContext().setAuthentication(jwtAuthenticationToken("unknown-sub"));

        assertThat(service.getCurrentActorId()).isNull();
        assertThat(service.getCurrentActorRole()).isNull();
        assertThat(service.isAdmin()).isFalse();
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
        Mockito.verify(userRepository, Mockito.never()).findByKeycloakSub(org.mockito.ArgumentMatchers.any());
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

        Mockito.verify(userRepository, Mockito.times(1)).findByKeycloakSub("keycloak-sub-1");
    }

    private JwtAuthenticationToken jwtAuthenticationToken(String subject) {
        return new JwtAuthenticationToken(JwtTestFixtures.jwt(subject));
    }

    /** 実際にKeycloakのクライアント設定次第でsubクレームが欠落したトークンが返るケースを再現する。 */
    private JwtAuthenticationToken jwtAuthenticationTokenWithoutSubject() {
        return new JwtAuthenticationToken(JwtTestFixtures.serviceJwt("admin-cli"));
    }
}
