package com.letsblog.identity.integration;

import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.identity.domain.User;
import com.letsblog.identity.keycloak.KeycloakAdminClient;
import com.letsblog.identity.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #816: 無効化されたユーザーの発行済みアクセストークンが認可を通り続ける問題。
 *
 * <p>{@code deactivate}はKeycloak側とローカルの{@code users.enabled}を落とすが、
 * <b>すでに発行済みのアクセストークンは失効しない</b>。Keycloakが止めるのは新規のトークン発行だけで、
 * 既存トークンの署名も有効期限も変わらない。#816以前は{@code CurrentActorService}が
 * {@code enabled}を参照していなかったため、無効化直後のユーザーは
 * {@code accessTokenLifespan}(既定300秒)の間、admin操作を含めて通常どおりAPIを通せた。
 *
 * <p>本テストはJWT自体は有効(署名・有効期限・issuerは検証を通る)なまま、
 * ローカルの{@code enabled}だけがfalseという、まさにその状態を再現する。
 *
 * <p>identity-service以外の9サービスは{@code GET /api/identity/me}経由で操作者を解決するため、
 * ここが塞がれば全サービスに効く。その前提を{@code /api/identity/me}のケースで固定している。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("identity-service: 無効化ユーザーの発行済みトークン(issue #816)")
class DisabledUserTokenIntegrationTest {

    private static final String ADMIN_SUB = "sub-816-admin";
    private static final String USER_SUB = "sub-816-user";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private KeycloakAdminClient keycloakAdminClient;

    private Long adminId;

    @BeforeEach
    void setUp() {
        userRepository.findByKeycloakSub(ADMIN_SUB).ifPresent(userRepository::delete);
        userRepository.findByKeycloakSub(USER_SUB).ifPresent(userRepository::delete);
        adminId = create(ADMIN_SUB, "admin", true);
        create(USER_SUB, "user", true);
    }

    /** @param enabled falseなら「無効化済みだが有効なトークンを持っている」状態。 */
    private Long create(String keycloakSub, String role, boolean enabled) {
        User user = new User();
        user.setEmail("issue816-" + keycloakSub + "-" + System.nanoTime() + "@example.test");
        user.setKeycloakSub(keycloakSub);
        user.setPasswordHash("not-used-keycloak-handles-authentication");
        user.setRole(role);
        user.setEnabled(enabled);
        return userRepository.save(user).getId();
    }

    private void setEnabled(String keycloakSub, boolean enabled) {
        User user = userRepository.findByKeycloakSub(keycloakSub).orElseThrow();
        user.setEnabled(enabled);
        userRepository.save(user);
    }

    // ------------------------------------------------- 無効化ユーザーは操作者として解決されない

    /**
     * 他サービスはこのエンドポイント経由で操作者を解決する。ここが403になることで、
     * 呼び出し側は「操作者なし」として扱う。#816の修正が全サービスへ波及する要。
     */
    @Test
    @DisplayName("無効化ユーザーは /api/identity/me を通れない(他サービスの解決経路)")
    void 無効化ユーザーはmeを通れない() throws Exception {
        setEnabled(USER_SUB, false);

        mockMvc.perform(request(HttpMethod.GET, "/api/identity/me")
                        .with(JwtTestFixtures.jwtRequestPostProcessor(USER_SUB, "user")))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/api/identity/me", "/api/identity/me/permissions"})
    @DisplayName("無効化ユーザーは自分自身の情報も取得できない")
    void 無効化ユーザーは自己情報を取得できない(String path) throws Exception {
        setEnabled(USER_SUB, false);

        mockMvc.perform(request(HttpMethod.GET, path)
                        .with(JwtTestFixtures.jwtRequestPostProcessor(USER_SUB, "user")))
                .andExpect(status().isForbidden());
    }

    /**
     * #816の核心。無効化されたadminは、手元のトークンが生きている間に
     * 自分自身を再有効化して復帰できてはならない。
     *
     * <p>これは#798で「reactivateに自己ガードを入れない」と判断した際の前提でもある。
     * 当時は「自己ガードを足しても、その5分間に本人は他のadminを無効化できるので気休め」と
     * 整理し、根本対処は本Issueに委ねた。
     */
    @Test
    @DisplayName("無効化されたadminは自分自身をreactivateできない(#816の核心)")
    void 無効化adminは自己reactivateできない() throws Exception {
        setEnabled(ADMIN_SUB, false);

        mockMvc.perform(request(HttpMethod.POST, "/api/users/" + adminId + "/reactivate")
                        .with(JwtTestFixtures.jwtRequestPostProcessor(ADMIN_SUB, "admin")))
                .andExpect(status().isForbidden());

        assertThat(userRepository.findById(adminId).orElseThrow().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("無効化されたadminはadmin限定エンドポイントを通れない")
    void 無効化adminはadmin操作をできない() throws Exception {
        setEnabled(ADMIN_SUB, false);

        mockMvc.perform(request(HttpMethod.GET, "/api/users")
                        .with(JwtTestFixtures.jwtRequestPostProcessor(ADMIN_SUB, "admin")))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------- 有効なユーザーは従来どおり

    @Test
    @DisplayName("有効なユーザーは従来どおり /api/identity/me を通れる")
    void 有効なユーザーはmeを通れる() throws Exception {
        mockMvc.perform(request(HttpMethod.GET, "/api/identity/me")
                        .with(JwtTestFixtures.jwtRequestPostProcessor(USER_SUB, "user")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("有効なadminは従来どおりadmin限定エンドポイントを通れる")
    void 有効なadminはadmin操作ができる() throws Exception {
        mockMvc.perform(request(HttpMethod.GET, "/api/users")
                        .with(JwtTestFixtures.jwtRequestPostProcessor(ADMIN_SUB, "admin")))
                .andExpect(status().isOk());
    }

    /**
     * 再有効化されれば元どおり通れること。無効化の判定がキャッシュ等で固定化されていないかの確認。
     */
    @Test
    @DisplayName("再有効化すれば再び通れる")
    void 再有効化すれば通れる() throws Exception {
        setEnabled(USER_SUB, false);
        mockMvc.perform(request(HttpMethod.GET, "/api/identity/me")
                        .with(JwtTestFixtures.jwtRequestPostProcessor(USER_SUB, "user")))
                .andExpect(status().isForbidden());

        setEnabled(USER_SUB, true);
        mockMvc.perform(request(HttpMethod.GET, "/api/identity/me")
                        .with(JwtTestFixtures.jwtRequestPostProcessor(USER_SUB, "user")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("未認証は401(認可判定より手前でSpring Securityが弾く)")
    void 未認証は401() throws Exception {
        mockMvc.perform(request(HttpMethod.GET, "/api/identity/me"))
                .andExpect(status().isUnauthorized());
    }
}
