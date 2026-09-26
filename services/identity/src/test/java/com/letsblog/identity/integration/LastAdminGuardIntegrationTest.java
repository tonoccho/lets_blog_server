package com.letsblog.identity.integration;

import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.identity.domain.User;
import com.letsblog.identity.keycloak.KeycloakAdminClient;
import com.letsblog.identity.repository.UserRepository;
import com.letsblog.identity.service.ForbiddenException;
import com.letsblog.identity.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #1162: 最後に残った有効なadminの削除・無効化を拒否する(#798の「数えない」判断を覆す)。
 *
 * <p>このテストが実MySQLに対して行う理由: 守るべき性質は「同時実行下でも有効なadminが0人にならない」
 * という原子性であり、モックでは検証できない(行ロックの有無はDBでしか観測できない)。
 *
 * <p><b>受け入れ基準のうち「唯一の有効なadminが対象」になる状態はHTTP(Web/API)越しには作れない。</b>
 * 操作者は有効なadminでなければ403で弾かれ(#816)、かつ自分自身は対象にできない(#796/#798)ため、
 * 「操作者(有効なadmin)≠対象(有効なadmin)」なら有効なadminは必ず2人以上いる。0/1人の状態に
 * なるのは同時実行のレースだけで、それを利用者視点のGherkinで決定的に再現することも、共有の受け入れ環境で
 * 他のadminを消さずに再現することもできない。そのためサービス層を直接呼ぶこの統合テストで固定する。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("identity-service: 最後のadminの保護(issue #1162)")
class LastAdminGuardIntegrationTest {

    private static final String ADMIN_A_SUB = "sub-1162-admin-a";
    private static final String ADMIN_B_SUB = "sub-1162-admin-b";
    private static final int RACE_ROUNDS = 15;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private KeycloakAdminClient keycloakAdminClient;

    /** テスト専用スキーマ(ADR-0006)上で、他テストが残した有効なadminを一時的に無効にした記録。 */
    private final List<Long> disabledOthers = new ArrayList<>();
    private Long adminAId;
    private Long adminBId;

    @BeforeEach
    void setUp() {
        cleanup();
        // 「有効なadminがちょうど2人」の状態を決定的にするため、他に有効なadminがいれば無効にしておく。
        for (User other : userRepository.findAll()) {
            if ("admin".equals(other.getRole()) && other.isEnabled()) {
                other.setEnabled(false);
                userRepository.save(other);
                disabledOthers.add(other.getId());
            }
        }
        adminAId = createAdmin(ADMIN_A_SUB);
        adminBId = createAdmin(ADMIN_B_SUB);
    }

    @AfterEach
    void tearDown() {
        cleanup();
        for (Long id : disabledOthers) {
            userRepository.findById(id).ifPresent(u -> {
                u.setEnabled(true);
                userRepository.save(u);
            });
        }
        disabledOthers.clear();
    }

    private void cleanup() {
        userRepository.findByKeycloakSub(ADMIN_A_SUB).ifPresent(userRepository::delete);
        userRepository.findByKeycloakSub(ADMIN_B_SUB).ifPresent(userRepository::delete);
    }

    private Long createAdmin(String keycloakSub) {
        User user = new User();
        user.setEmail("issue1162-" + keycloakSub + "-" + System.nanoTime() + "@example.test");
        // keycloak_sub未設定だとKeycloak呼び出しを伴わないが、JWT経由のHTTP検証には設定が要る。
        user.setKeycloakSub(keycloakSub);
        user.setPasswordHash("not-used-keycloak-handles-authentication");
        user.setRole("admin");
        user.setEnabled(true);
        return userRepository.save(user).getId();
    }

    private long enabledAdminCount() {
        return userRepository.findAll().stream()
                .filter(u -> "admin".equals(u.getRole()) && u.isEnabled())
                .count();
    }

    // ---------------------------------------------- 唯一の有効なadmin(サービス層。HTTPでは到達不能)

    @Test
    @DisplayName("有効なadminが1人だけのとき、その削除は拒否され、adminは残る")
    void 唯一のadminは削除できない() {
        userRepository.findById(adminBId).ifPresent(u -> {
            u.setEnabled(false);
            userRepository.save(u);
        });

        assertThatThrownBy(() -> userService.delete(adminAId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("最後の管理者");

        assertThat(userRepository.existsById(adminAId)).isTrue();
    }

    @Test
    @DisplayName("有効なadminが1人だけのとき、その無効化は拒否され、adminは有効なまま")
    void 唯一のadminは無効化できない() {
        userRepository.findById(adminBId).ifPresent(u -> {
            u.setEnabled(false);
            userRepository.save(u);
        });

        assertThatThrownBy(() -> userService.deactivate(adminAId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("最後の管理者");

        assertThat(userRepository.findById(adminAId).orElseThrow().isEnabled()).isTrue();
    }

    // ---------------------------------------------- 2人以上なら互いに操作できる(回帰)

    @Test
    @DisplayName("adminが2人いれば、一方は他方を削除できる(HTTP)")
    void 二人いれば削除できる() throws Exception {
        mockMvc.perform(request(HttpMethod.DELETE, "/api/users/" + adminBId)
                        .with(JwtTestFixtures.jwtRequestPostProcessor(ADMIN_A_SUB, "admin")))
                .andExpect(status().isNoContent());

        assertThat(userRepository.existsById(adminBId)).isFalse();
        assertThat(enabledAdminCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("adminが2人いれば、一方は他方を無効化できる(HTTP)")
    void 二人いれば無効化できる() throws Exception {
        mockMvc.perform(request(HttpMethod.POST, "/api/users/" + adminBId + "/deactivate")
                        .with(JwtTestFixtures.jwtRequestPostProcessor(ADMIN_A_SUB, "admin")))
                .andExpect(status().isOk());

        assertThat(userRepository.findById(adminBId).orElseThrow().isEnabled()).isFalse();
    }

    // ---------------------------------------------- 同時実行(TOCTOU)

    @Test
    @DisplayName("2人のadminが互いを同時に削除しても、最後の1人は残る")
    void 同時削除でも一人残る() throws Exception {
        for (int round = 0; round < RACE_ROUNDS; round++) {
            if (round > 0) {
                cleanup();
                adminAId = createAdmin(ADMIN_A_SUB);
                adminBId = createAdmin(ADMIN_B_SUB);
            }
            Long a = adminAId;
            Long b = adminBId;
            int succeeded = race(() -> userService.delete(b), () -> userService.delete(a));

            assertThat(succeeded).as("round %d: 成功した削除の数", round).isEqualTo(1);
            assertThat(enabledAdminCount()).as("round %d: 残った有効なadmin", round).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("2人のadminが互いを同時に無効化しても、最後の1人は有効なまま残る")
    void 同時無効化でも一人残る() throws Exception {
        for (int round = 0; round < RACE_ROUNDS; round++) {
            if (round > 0) {
                cleanup();
                adminAId = createAdmin(ADMIN_A_SUB);
                adminBId = createAdmin(ADMIN_B_SUB);
            }
            Long a = adminAId;
            Long b = adminBId;
            int succeeded = race(() -> userService.deactivate(b), () -> userService.deactivate(a));

            assertThat(succeeded).as("round %d: 成功した無効化の数", round).isEqualTo(1);
            assertThat(enabledAdminCount()).as("round %d: 残った有効なadmin", round).isEqualTo(1);
        }
    }

    /**
     * 2つの操作を同時に開始し、成功した数を返す。拒否({@link ForbiddenException})以外の例外は
     * 想定外なので、そのまま投げ直してテストを失敗させる。
     */
    private int race(Runnable first, Runnable second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Callable<Boolean>> tasks = List.of(gated(first, ready, go), gated(second, ready, go));
            List<Future<Boolean>> futures = new ArrayList<>();
            for (Callable<Boolean> task : tasks) {
                futures.add(pool.submit(task));
            }
            ready.await();
            go.countDown();
            int succeeded = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    succeeded++;
                }
            }
            return succeeded;
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<Boolean> gated(Runnable operation, CountDownLatch ready, CountDownLatch go) {
        return () -> {
            ready.countDown();
            go.await();
            try {
                operation.run();
                return true;
            } catch (ForbiddenException refused) {
                return false;
            }
        };
    }
}
