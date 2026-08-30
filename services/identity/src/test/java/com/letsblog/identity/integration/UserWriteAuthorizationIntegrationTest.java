package com.letsblog.identity.integration;

import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.identity.domain.User;
import com.letsblog.identity.keycloak.KeycloakAdminClient;
import com.letsblog.identity.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #796: {@code UserController}の書き込み系エンドポイントの認可。
 *
 * <p>#653で{@code GET /api/users}に{@code requireAdmin()}が入った際、
 * {@code POST /api/users}・{@code PATCH /api/users/{id}}・{@code DELETE /api/users/{id}}の3つが
 * 取り残されていた。#772で未認証は401になったが、<b>認証済みの一般ユーザーなら</b>任意ユーザーの
 * 削除・改変と、{@code role=admin}のアカウント作成(権限昇格)ができる状態だった。
 * {@code UserUpdateRequest}が{@code role}を受け取るため、自分自身をadminへ昇格させることも可能だった。
 *
 * <p>Keycloak Admin APIは外部境界のため{@link KeycloakAdminClient}を{@code @MockitoBean}で
 * 置き換える(ADR-0006のモック方針)。認可で弾かれる経路では、そもそもここへ到達しないことも
 * 併せて検証する(下流に副作用が及んでいないこと)。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("identity-service: ユーザー書き込みAPIの認可(issue #796)")
class UserWriteAuthorizationIntegrationTest {

    private static final String ADMIN_SUB = "sub-796-admin";
    private static final String USER_SUB = "sub-796-user";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private KeycloakAdminClient keycloakAdminClient;

    private Long adminId;
    private Long targetId;

    @BeforeEach
    void setUp() {
        adminId = recreate(ADMIN_SUB, "admin");
        targetId = recreate(USER_SUB, "user");
    }

    private Long recreate(String keycloakSub, String role) {
        userRepository.findByKeycloakSub(keycloakSub).ifPresent(userRepository::delete);
        User user = new User();
        user.setEmail("issue796-" + keycloakSub + "-" + System.nanoTime() + "@example.test");
        user.setKeycloakSub(keycloakSub);
        user.setPasswordHash("not-used-keycloak-handles-authentication");
        user.setRole(role);
        return userRepository.save(user).getId();
    }

    // ---------------------------------------------------------------- 非adminは弾かれる

    @Test
    @DisplayName("非adminはユーザーを作成できない(role=adminのアカウントを作れない)")
    void 非adminはユーザーを作成できない() throws Exception {
        long before = userRepository.count();

        mockMvc.perform(request(HttpMethod.POST, "/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"escalate@example.test\",\"password\":\"pw\",\"role\":\"admin\"}")
                        .with(JwtTestFixtures.jwtRequestPostProcessor(USER_SUB, "user")))
                .andExpect(status().isForbidden());

        assertThat(userRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("非adminは他ユーザーのroleを変更できない(自己昇格もできない)")
    void 非adminはroleを変更できない() throws Exception {
        mockMvc.perform(request(HttpMethod.PATCH, "/api/users/" + targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"admin\"}")
                        .with(JwtTestFixtures.jwtRequestPostProcessor(USER_SUB, "user")))
                .andExpect(status().isForbidden());

        assertThat(userRepository.findById(targetId).orElseThrow().getRole()).isEqualTo("user");
    }

    @Test
    @DisplayName("非adminはユーザーを削除できない")
    void 非adminは削除できない() throws Exception {
        mockMvc.perform(request(HttpMethod.DELETE, "/api/users/" + adminId)
                        .with(JwtTestFixtures.jwtRequestPostProcessor(USER_SUB, "user")))
                .andExpect(status().isForbidden());

        assertThat(userRepository.findById(adminId)).isPresent();
    }

    // ---------------------------------------------------------------- adminは従来どおり通る

    @Test
    @DisplayName("adminは他ユーザーのroleを変更できる")
    void adminはroleを変更できる() throws Exception {
        mockMvc.perform(request(HttpMethod.PATCH, "/api/users/" + targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"admin\"}")
                        .with(JwtTestFixtures.jwtRequestPostProcessor(ADMIN_SUB, "admin")))
                .andExpect(status().isOk());

        assertThat(userRepository.findById(targetId).orElseThrow().getRole()).isEqualTo("admin");
    }

    @Test
    @DisplayName("adminは他ユーザーを削除できる")
    void adminは他ユーザーを削除できる() throws Exception {
        mockMvc.perform(request(HttpMethod.DELETE, "/api/users/" + targetId)
                        .with(JwtTestFixtures.jwtRequestPostProcessor(ADMIN_SUB, "admin")))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findById(targetId)).isEmpty();
    }

    // ---------------------------------------------------------------- 自己削除の禁止

    /**
     * Web側({@code web/src/app/users/actions.ts})にも同じガードがあるが、gatewayは認可判定を
     * 行わない(ADR-0008)ため、アクセストークンを持つクライアントはAPIを直接叩ける。
     * サーバー側の backstop が無いと、最後のadminが自分を消して誰も管理できない状態になりうる。
     */
    @Test
    @DisplayName("adminでも自分自身は削除できない")
    void adminでも自己削除はできない() throws Exception {
        mockMvc.perform(request(HttpMethod.DELETE, "/api/users/" + adminId)
                        .with(JwtTestFixtures.jwtRequestPostProcessor(ADMIN_SUB, "admin")))
                .andExpect(status().isForbidden());

        assertThat(userRepository.findById(adminId)).isPresent();
    }

    // ---------------------------------------------------------------- 認証ゲート(#772)

    @Test
    @DisplayName("未認証は401(認可判定より手前でSpring Securityが弾く)")
    void 未認証は401() throws Exception {
        mockMvc.perform(request(HttpMethod.DELETE, "/api/users/" + targetId))
                .andExpect(status().isUnauthorized());

        assertThat(userRepository.findById(targetId)).isPresent();
    }
}
