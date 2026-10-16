package com.letsblog.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.identity.domain.User;
import com.letsblog.identity.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;

/**
 * 操作者の解決。リクエストはSpring Testの{@link MockHttpServletRequest}(実物)、DBは
 * Spring Dataのリポジトリ(外部との通信)だけをMockitoで差し替える。
 */
class CurrentActorServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);

    private MockHttpServletRequest request;
    private CurrentActorService service;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
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
        user.setEnabled(true);
        when(userRepository.findByKeycloakSub("keycloak-sub-1")).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(jwtAuthenticationToken("keycloak-sub-1"));

        assertThat(service.getCurrentActorId()).isEqualTo(99L);
        assertThat(service.getCurrentActorRole()).isEqualTo("admin");
        assertThat(service.isAdmin()).isTrue();
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
    void 無効化されたユーザーは操作者として解決しない() {
        User user = new User();
        user.setId(5L);
        user.setRole("admin");
        user.setEnabled(false);
        when(userRepository.findByKeycloakSub("disabled-sub")).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(jwtAuthenticationToken("disabled-sub"));

        assertThat(service.getCurrentActorId()).isNull();
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
        verify(userRepository, never()).findByKeycloakSub(ArgumentMatchers.any());
    }

    @Test
    void JWTのsubクレームが空白の場合もユーザーを引かない() {
        SecurityContextHolder.getContext().setAuthentication(jwtAuthenticationToken("   "));

        assertThat(service.getCurrentActorId()).isNull();
        verify(userRepository, never()).findByKeycloakSub(ArgumentMatchers.any());
    }

    @Test
    void JWT解決結果は1リクエストにつき1回だけDBへ問い合わせる() {
        User user = new User();
        user.setId(1L);
        user.setRole("user");
        user.setEnabled(true);
        when(userRepository.findByKeycloakSub("keycloak-sub-1")).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(jwtAuthenticationToken("keycloak-sub-1"));

        service.getCurrentActorId();
        service.getCurrentActorRole();
        service.isAdmin();

        verify(userRepository, times(1)).findByKeycloakSub("keycloak-sub-1");
    }

    @Test
    void 操作者なしの解決結果もリクエスト内で使い回す() {
        service.getCurrentActorId();
        service.getCurrentActorRole();

        assertThat(request.getAttribute(CurrentActorService.class.getName() + ".jwtActor"))
                .isEqualTo(Optional.empty());
    }

    @Test
    void getCurrentActorKeycloakSubはJWTのsubを返す() {
        SecurityContextHolder.getContext().setAuthentication(jwtAuthenticationToken("keycloak-sub-9"));

        assertThat(service.getCurrentActorKeycloakSub()).isEqualTo("keycloak-sub-9");
    }

    @Test
    void getCurrentActorKeycloakSubは認証が無ければnull() {
        assertThat(service.getCurrentActorKeycloakSub()).isNull();
    }

    @Test
    void getCurrentActorKeycloakSubはJWT以外の認証ならnull() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("user", "pw"));

        assertThat(service.getCurrentActorKeycloakSub()).isNull();
        assertThat(service.getCurrentActorId()).isNull();
    }

    @Test
    void getRemoteIpはX_Forwarded_Forがあればそれを返す() {
        request.addHeader("X-Forwarded-For", "203.0.113.5");
        request.setRemoteAddr("10.0.0.1");

        assertThat(service.getRemoteIp()).isEqualTo("203.0.113.5");
    }

    @Test
    void getRemoteIpはX_Forwarded_Forが空白ならリモートアドレスを返す() {
        request.addHeader("X-Forwarded-For", "  ");
        request.setRemoteAddr("10.0.0.2");

        assertThat(service.getRemoteIp()).isEqualTo("10.0.0.2");
    }

    @Test
    void getRemoteIpはX_Forwarded_Forが無ければリモートアドレスを返す() {
        request.setRemoteAddr("10.0.0.3");

        assertThat(service.getRemoteIp()).isEqualTo("10.0.0.3");
    }

    @Test
    void getUserAgentはヘッダーの値を返す() {
        request.addHeader("User-Agent", "JUnit/5");

        assertThat(service.getUserAgent()).isEqualTo("JUnit/5");
    }

    private JwtAuthenticationToken jwtAuthenticationToken(String subject) {
        return new JwtAuthenticationToken(JwtTestFixtures.jwt(subject));
    }

    /** 実際にKeycloakのクライアント設定次第でsubクレームが欠落したトークンが返るケースを再現する。 */
    private JwtAuthenticationToken jwtAuthenticationTokenWithoutSubject() {
        return new JwtAuthenticationToken(JwtTestFixtures.serviceJwt("admin-cli"));
    }
}
