package com.letsblog.identity.integration;

import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.identity.domain.Permission;
import com.letsblog.identity.domain.Role;
import com.letsblog.identity.domain.User;
import com.letsblog.identity.keycloak.KeycloakAdminClient;
import com.letsblog.identity.repository.RoleRepository;
import com.letsblog.identity.repository.UserRepository;
import java.util.HashSet;
import java.util.Set;
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
 * issue #798: 自分自身に対する特権操作で、自分または運用者が回復不能な状態に陥る経路を塞ぐ。
 *
 * <p>#796が{@code POST/PATCH/DELETE /api/users}を塞いだ際、隣接する2経路が残っていた。
 *
 * <ol>
 *   <li>{@code POST/DELETE /api/users/{userId}/roles/{roleName}}は
 *       {@code requirePermission(ROLE_MANAGE)}だけで守られており、admin判定ではなかった。
 *       既定シードで{@code ROLE_MANAGE}を持つのは{@code ROLE_ADMIN}のみなので既定データでは
 *       実害が無いが、運用で非adminロールに{@code ROLE_MANAGE}を付与すると、そのユーザーは
 *       自分に特権ロールを付けられる。</li>
 *   <li>{@code POST /api/users/{id}/deactivate}に自己ガードが無く、adminが自分を無効化して
 *       締め出される経路が残っていた({@code reactivate}も{@code requireAdmin()}を要求する)。</li>
 * </ol>
 *
 * <p>本サービスのテストはFlywayを持たず{@code ddl-auto: create-drop}で空スキーマから始まるため
 * ({@code application-test.yml}参照)、{@code V8__add_rbac_tables.sql}のシードは入らない。
 * ロールと権限の組み合わせはこのテスト自身が明示的に組み立てる。かえって
 * 「どの権限を持つロールが特権とみなされるか」が読んで分かるので、この方が意図に合う。
 *
 * <p>Keycloak Admin APIは外部境界のため{@code @MockitoBean}で置き換える(ADR-0006)。
 * 認可で弾かれる経路では下流に副作用が及んでいないことも併せて検証する。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("identity-service: 自己権限昇格・自己締め出しの防止(issue #798)")
class SelfPrivilegeEscalationIntegrationTest {

    private static final String ADMIN_SUB = "sub-798-admin";
    /** {@code users.role}は"user"だが、RBAC上は{@code ROLE_MANAGE}を持つ操作者。 */
    private static final String OPERATOR_SUB = "sub-798-operator";
    private static final String PLAIN_SUB = "sub-798-plain";

    private static final String PRIVILEGED_ROLE = "ROLE_ADMIN";
    private static final String OPERATOR_ROLE = "ROLE_OPERATOR";
    private static final String PLAIN_ROLE = "ROLE_EDITOR";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private KeycloakAdminClient keycloakAdminClient;

    private Long adminId;
    private Long operatorId;
    private Long plainId;

    @BeforeEach
    void setUp() {
        // ロールより先にユーザーを消す。user_rolesがrolesを参照しているため、
        // 逆順にすると外部キー制約で落ちる。
        userRepository.findByKeycloakSub(ADMIN_SUB).ifPresent(userRepository::delete);
        userRepository.findByKeycloakSub(OPERATOR_SUB).ifPresent(userRepository::delete);
        userRepository.findByKeycloakSub(PLAIN_SUB).ifPresent(userRepository::delete);

        // ROLE_MANAGEを持つロール = 特権ロール。ROLE_ADMINとROLE_OPERATORの2つを用意し、
        // 「特権ロールかどうかは名前ではなく権限で決まる」ことをテスト自体で表現する。
        // ロールは削除せず権限を上書きする(他のテストクラスが作った関連が残っていても壊さない)。
        ensureRole(PRIVILEGED_ROLE, Set.of(Permission.ROLE_MANAGE, Permission.USER_ROLE_MANAGE));
        ensureRole(OPERATOR_ROLE, Set.of(Permission.ROLE_MANAGE));
        ensureRole(PLAIN_ROLE, Set.of(Permission.POST_CREATE, Permission.POST_READ));

        adminId = createUser(ADMIN_SUB, "admin", null);
        operatorId = createUser(OPERATOR_SUB, "user", OPERATOR_ROLE);
        plainId = createUser(PLAIN_SUB, "user", null);
    }

    private void ensureRole(String roleName, Set<Permission> permissions) {
        Role role = roleRepository.findByRoleName(roleName).orElseGet(() -> new Role(roleName, roleName));
        role.setPermissions(new HashSet<>(permissions));
        roleRepository.save(role);
    }

    private Long createUser(String keycloakSub, String role, String rbacRoleName) {
        User user = new User();
        user.setEmail("issue798-" + keycloakSub + "-" + System.nanoTime() + "@example.test");
        user.setKeycloakSub(keycloakSub);
        user.setPasswordHash("not-used-keycloak-handles-authentication");
        user.setRole(role);
        if (rbacRoleName != null) {
            user.getRoles().add(roleRepository.findByRoleName(rbacRoleName).orElseThrow());
        }
        return userRepository.save(user).getId();
    }

    private boolean hasRbacRole(Long userId, String roleName) {
        return userRepository.findById(userId).orElseThrow().getRoles().stream()
                .anyMatch(r -> r.getRoleName().equals(roleName));
    }

    // ------------------------------------------------- 1. 自己権限昇格(ロール割り当て)

    @Test
    @DisplayName("ROLE_MANAGE保有の非adminは、自分に特権ロールを付与できない")
    void 非adminは自分に特権ロールを付与できない() throws Exception {
        mockMvc.perform(request(HttpMethod.POST, "/api/users/" + operatorId + "/roles/" + PRIVILEGED_ROLE)
                        .with(JwtTestFixtures.jwtRequestPostProcessor(OPERATOR_SUB, "user")))
                .andExpect(status().isForbidden());

        assertThat(hasRbacRole(operatorId, PRIVILEGED_ROLE)).isFalse();
    }

    @Test
    @DisplayName("ROLE_MANAGE保有の非adminは、他人にも特権ロールを付与できない(共謀・別アカウント経由の迂回を塞ぐ)")
    void 非adminは他人にも特権ロールを付与できない() throws Exception {
        mockMvc.perform(request(HttpMethod.POST, "/api/users/" + plainId + "/roles/" + PRIVILEGED_ROLE)
                        .with(JwtTestFixtures.jwtRequestPostProcessor(OPERATOR_SUB, "user")))
                .andExpect(status().isForbidden());

        assertThat(hasRbacRole(plainId, PRIVILEGED_ROLE)).isFalse();
    }

    /**
     * MySQLのスキーマは{@code utf8mb4_unicode_ci}(大文字小文字を区別せず、末尾空白も無視)のため、
     * {@code findByRoleName("role_admin")}は{@code ROLE_ADMIN}の行に一致する。
     * 認可をロール名の文字列一致({@code "ROLE_ADMIN".equals(roleName)})で書くと、
     * 大小を変えただけの入力でガードだけをすり抜け、割り当て処理では同じ行に解決される、
     * という迂回が成立する。実装はDBから解決した実体の権限で判定しているため塞がっている。
     */
    @ParameterizedTest(name = "ロール名 \"{0}\" でも迂回できない")
    @ValueSource(strings = {"role_admin", "Role_Admin", "ROLE_ADMIN "})
    @DisplayName("照合規則の揺れ(大文字小文字・末尾空白)でガードを迂回できない")
    void 照合規則の揺れで迂回できない(String roleName) throws Exception {
        mockMvc.perform(request(HttpMethod.POST, "/api/users/" + operatorId + "/roles/" + roleName.strip())
                        .with(JwtTestFixtures.jwtRequestPostProcessor(OPERATOR_SUB, "user")))
                .andExpect(status().isForbidden());

        assertThat(hasRbacRole(operatorId, PRIVILEGED_ROLE)).isFalse();
    }

    @Test
    @DisplayName("ROLE_MANAGE保有の非adminは、特権ロールを剥奪もできない(adminからRBACロールを剥がして回れない)")
    void 非adminは特権ロールを剥奪できない() throws Exception {
        mockMvc.perform(request(HttpMethod.DELETE, "/api/users/" + operatorId + "/roles/" + OPERATOR_ROLE)
                        .with(JwtTestFixtures.jwtRequestPostProcessor(OPERATOR_SUB, "user")))
                .andExpect(status().isForbidden());

        assertThat(hasRbacRole(operatorId, OPERATOR_ROLE)).isTrue();
    }

    /**
     * 特権でないロールの付与・剥奪は{@code ROLE_MANAGE}のままにしている。
     * ここを塞いでしまうと{@code ROLE_MANAGE}という権限自体が無意味になり、
     * 本Issueの目的(昇格経路を塞ぐ)を超えて既存の運用を壊す。
     */
    @Test
    @DisplayName("ROLE_MANAGE保有の非adminは、特権でないロールなら従来どおり付与できる")
    void 非adminでも非特権ロールは付与できる() throws Exception {
        mockMvc.perform(request(HttpMethod.POST, "/api/users/" + plainId + "/roles/" + PLAIN_ROLE)
                        .with(JwtTestFixtures.jwtRequestPostProcessor(OPERATOR_SUB, "user")))
                .andExpect(status().isOk());

        assertThat(hasRbacRole(plainId, PLAIN_ROLE)).isTrue();
    }

    @Test
    @DisplayName("adminは特権ロールを付与できる")
    void adminは特権ロールを付与できる() throws Exception {
        mockMvc.perform(request(HttpMethod.POST, "/api/users/" + plainId + "/roles/" + PRIVILEGED_ROLE)
                        .with(JwtTestFixtures.jwtRequestPostProcessor(ADMIN_SUB, "admin")))
                .andExpect(status().isOk());

        assertThat(hasRbacRole(plainId, PRIVILEGED_ROLE)).isTrue();
    }

    @Test
    @DisplayName("ROLE_MANAGEを持たない一般ユーザーは、そもそもロールを付与できない")
    void 権限なしはロールを付与できない() throws Exception {
        mockMvc.perform(request(HttpMethod.POST, "/api/users/" + plainId + "/roles/" + PLAIN_ROLE)
                        .with(JwtTestFixtures.jwtRequestPostProcessor(PLAIN_SUB, "user")))
                .andExpect(status().isForbidden());

        assertThat(hasRbacRole(plainId, PLAIN_ROLE)).isFalse();
    }

    // ------------------------------------------------- 2. 自己無効化によるロックアウト

    @Test
    @DisplayName("adminでも自分自身は無効化できない")
    void adminでも自己無効化はできない() throws Exception {
        mockMvc.perform(request(HttpMethod.POST, "/api/users/" + adminId + "/deactivate")
                        .with(JwtTestFixtures.jwtRequestPostProcessor(ADMIN_SUB, "admin")))
                .andExpect(status().isForbidden());

        assertThat(userRepository.findById(adminId).orElseThrow().isEnabled()).isTrue();
    }

    /**
     * adminが2人いれば互いに無効化できるのは正当な運用なので、そこは塞がない
     * ({@code AdminAuthorizationService#requireAdminAndNotSelf}のJavadoc参照)。
     */
    @Test
    @DisplayName("adminは他ユーザーを無効化できる")
    void adminは他ユーザーを無効化できる() throws Exception {
        mockMvc.perform(request(HttpMethod.POST, "/api/users/" + plainId + "/deactivate")
                        .with(JwtTestFixtures.jwtRequestPostProcessor(ADMIN_SUB, "admin")))
                .andExpect(status().isOk());

        assertThat(userRepository.findById(plainId).orElseThrow().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("非adminは他ユーザーを無効化できない")
    void 非adminは無効化できない() throws Exception {
        mockMvc.perform(request(HttpMethod.POST, "/api/users/" + plainId + "/deactivate")
                        .with(JwtTestFixtures.jwtRequestPostProcessor(OPERATOR_SUB, "user")))
                .andExpect(status().isForbidden());

        assertThat(userRepository.findById(plainId).orElseThrow().isEnabled()).isTrue();
    }

    // ------------------------------------------------- 認証ゲート(#772)

    @Test
    @DisplayName("未認証は401(認可判定より手前でSpring Securityが弾く)")
    void 未認証は401() throws Exception {
        mockMvc.perform(request(HttpMethod.POST, "/api/users/" + operatorId + "/roles/" + PRIVILEGED_ROLE))
                .andExpect(status().isUnauthorized());

        assertThat(hasRbacRole(operatorId, PRIVILEGED_ROLE)).isFalse();
    }
}
