package com.letsblog.identity.integration;

import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.identity.domain.User;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #784: 自ユーザーの個人設定(言語・タイムゾーン)を、クライアントからユーザーIDを
 * 受け取らずに更新できることの統合テスト。
 *
 * <p>Keycloak移行(#564)以降、Webのセッションが持つのはKeycloakの{@code sub}(UUID)であり、
 * これを{@code Number()}で数値ユーザーIDへ変換すると必ず{@code NaN}になる。その結果
 * {@code PATCH /api/users/NaN/preferences}という壊れたリクエストが送られ続け、個人設定の
 * 保存が全ユーザーで常時失敗していた(24時間で203件の{@code /api/users/NaN}を観測)。
 *
 * <p>{@code PATCH /api/identity/me/preferences}は{@code CurrentActorService}が検証済みJWTの
 * {@code sub}から自ユーザーを解決するため、クライアント入力の識別子を一切受け取らない。
 * 本クラスは「解決成功」「JWTなし」「{@code keycloak_sub}が未同期」の3経路を検証する。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("identity-service: 自ユーザーの個人設定更新(issue #784)")
class SelfPreferencesIntegrationTest {

    private static final String PATH = "/api/identity/me/preferences";
    private static final String BODY = "{\"locale\":\"en\",\"timezone\":\"America/New_York\"}";
    private static final String KEYCLOAK_SUB = "sub-784-self";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private Long userId;

    @BeforeEach
    void setUp() {
        userRepository.findByKeycloakSub(KEYCLOAK_SUB).ifPresent(userRepository::delete);
        User user = new User();
        user.setEmail("issue784-" + System.nanoTime() + "@example.test");
        user.setKeycloakSub(KEYCLOAK_SUB);
        user.setPasswordHash("not-used-keycloak-handles-authentication");
        user.setRole("user");
        user.setLocale("ja");
        user.setTimezone("Asia/Tokyo");
        userId = userRepository.save(user).getId();
    }

    @Test
    @DisplayName("有効なJWTのsubからローカルユーザーを解決し、設定を保存する")
    void 自ユーザーの設定を保存できる() throws Exception {
        mockMvc.perform(request(HttpMethod.PATCH, PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY)
                        .with(JwtTestFixtures.jwtRequestPostProcessor(KEYCLOAK_SUB, "user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId))
                .andExpect(jsonPath("$.locale").value("en"))
                .andExpect(jsonPath("$.timezone").value("America/New_York"));

        User reloaded = userRepository.findById(userId).orElseThrow();
        assertThat(reloaded.getLocale()).isEqualTo("en");
        assertThat(reloaded.getTimezone()).isEqualTo("America/New_York");
    }

    /**
     * SecurityConfigの認証ゲート(issue #772でADR-0008の形へ揃えた)が、コントローラへ到達する前に弾く。
     */
    @Test
    @DisplayName("Authorizationヘッダーなしは401")
    void jwtなしは401() throws Exception {
        mockMvc.perform(request(HttpMethod.PATCH, PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());
    }

    /**
     * JWTは有効だが、その{@code sub}に対応するローカルユーザーが無い(Keycloakユーザー同期が
     * 未実施、または他realmのトークン)場合。{@code NaN}のときのような型変換エラー(400)ではなく、
     * 意味のある403を返す。
     */
    @Test
    @DisplayName("keycloak_subが未同期なら403(400ではない)")
    void 未同期のsubは403() throws Exception {
        mockMvc.perform(request(HttpMethod.PATCH, PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY)
                        .with(JwtTestFixtures.jwtRequestPostProcessor("sub-784-unknown", "user")))
                .andExpect(status().isForbidden());
    }

    /** 不正なタイムゾーンは既存のバリデーション(UserService)どおり400。 */
    @Test
    @DisplayName("不正なタイムゾーンは400")
    void 不正なタイムゾーンは400() throws Exception {
        mockMvc.perform(request(HttpMethod.PATCH, PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locale\":\"ja\",\"timezone\":\"Not/AZone\"}")
                        .with(JwtTestFixtures.jwtRequestPostProcessor(KEYCLOAK_SUB, "user")))
                .andExpect(status().isBadRequest());
    }
}
