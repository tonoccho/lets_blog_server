package com.letsblog.ai.integration;

import com.letsblog.ai.testsupport.BackendIntegrationTest;
import com.letsblog.ai.testsupport.IntegrationBackends;
import com.letsblog.common.testfixtures.JwtTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #644: ai-serviceに複製されたAdminAuthorizationService(requireProjectMemberOrAdmin、
 * issue #574)が、実際のController経由でも401/403を正しく返すことを検証する統合テスト。legacy-apiの
 * {@code AuthorizationMatrixIntegrationTest}と同じ観点((a)JWTが無効なら401、(b)/(c)admin/
 * プロジェクトメンバーの認可判定は403で表現される)を、ai-serviceの認可経路(CurrentActorServiceが
 * Authorizationヘッダーをidentity-serviceへ転送して解決する。IdentityBridgeClient経由の
 * プロジェクトメンバー判定)に合わせて{@link
 * com.letsblog.ai.controller.ArticlePlanController#listCategories}で検証する。
 *
 * <p>issue #1824: identity-serviceはHTTPフェイク({@link IntegrationBackends})で、{@code IdentityClient}・
 * {@code IdentityBridgeClient}はアプリケーションの実物のBean。モックのままなのはKeycloakの鍵取得を伴う
 * {@code JwtDecoder}だけ(ADR-0006のモック方針)。認可通過後に呼ばれる{@code listExistingCategories}の
 * publishing-serviceもHTTPフェイクが空の一覧を返す。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("ai-service: AdminAuthorizationServiceの認可マトリクス統合テスト(issue #644)")
class AdminAuthorizationIntegrationTest extends BackendIntegrationTest {

    private static final String CATEGORIES_PATH = "/api/projects/42/article-plan/categories";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void resetBackends() {
        IntegrationBackends.reset();
        IntegrationBackends.server().json("GET", "/api/internal/ai/projects/42/existing-categories", "[]");
    }

    /** identity-serviceが{@code /api/identity/me}を、指定のBearerで受けた回数。 */
    private static long lookups(String bearer) {
        return IntegrationBackends.server().requests("GET", "/api/identity/me").stream()
                .filter(request -> bearer.equals(request.header("Authorization"))).count();
    }

    @Test
    @DisplayName("Authorizationヘッダーなしは401(issue #772でSecurityConfigの認証ゲートを復元したため、"
            + "コントローラの認可チェック(403)へ到達する前にSpring Securityが弾く)")
    void authorizationヘッダーなしは401() throws Exception {
        mockMvc.perform(get(CATEGORIES_PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("不正なJWTは401(Spring Securityの認証フィルタで弾かれ、コントローラへ到達しない)")
    void 不正なjwtは401() throws Exception {
        when(jwtDecoder.decode("invalid-jwt")).thenThrow(new BadJwtException("invalid token"));

        mockMvc.perform(get(CATEGORIES_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer invalid-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("有効なJWTでも非member・非adminなら403")
    void 非メンバー非adminは403() throws Exception {
        when(jwtDecoder.decode("member-check-jwt")).thenReturn(JwtTestFixtures.jwt("sub-1", "user"));
        IntegrationBackends.actor("member-check-jwt", 10, "user");
        IntegrationBackends.membership(42L, 10L, false);

        mockMvc.perform(get(CATEGORIES_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer member-check-jwt"))
                .andExpect(status().isForbidden());

        // 操作者解決が実際にidentity-serviceへの問い合わせを通っていることを確かめる(issue #916)。
        assertThat(lookups("Bearer member-check-jwt")).isEqualTo(1);
    }

    @Test
    @DisplayName("プロジェクトメンバーなら403にならない")
    void プロジェクトメンバーは403にならない() throws Exception {
        when(jwtDecoder.decode("member-jwt")).thenReturn(JwtTestFixtures.jwt("sub-2", "user"));
        IntegrationBackends.actor("member-jwt", 11, "user");
        IntegrationBackends.membership(42L, 11L, true);

        mockMvc.perform(get(CATEGORIES_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer member-jwt"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));

        // 操作者解決が実際にidentity-serviceへの問い合わせを通っていることを確かめる(issue #916)。
        assertThat(lookups("Bearer member-jwt")).isEqualTo(1);
    }

    @Test
    @DisplayName("adminなら所属に関わらず403にならない(プロジェクトメンバー判定はバイパスされる)")
    void adminは403にならない() throws Exception {
        when(jwtDecoder.decode("admin-jwt")).thenReturn(JwtTestFixtures.jwt("sub-3", "admin"));
        IntegrationBackends.actor("admin-jwt", 1, "admin");

        mockMvc.perform(get(CATEGORIES_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer admin-jwt"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));

        // 操作者解決が実際にidentity-serviceへの問い合わせを通っていることを確かめる(issue #916)。
        assertThat(lookups("Bearer admin-jwt")).isEqualTo(1);
    }

    /** issue #1499: ai-connectionsもプロジェクトメンバーでなければ403(既存のarticle-planと同じ判定)。 */
    @Test
    @DisplayName("ai-connectionsは非member・非adminなら403(issue #1499)")
    void aiConnectionsは非メンバー非adminなら403() throws Exception {
        when(jwtDecoder.decode("ai-conn-jwt")).thenReturn(JwtTestFixtures.jwt("sub-9", "user"));
        IntegrationBackends.actor("ai-conn-jwt", 19, "user");
        IntegrationBackends.membership(42L, 19L, false);

        mockMvc.perform(get("/api/projects/42/ai-connections")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ai-conn-jwt"))
                .andExpect(status().isForbidden());

        assertThat(lookups("Bearer ai-conn-jwt")).isEqualTo(1);
    }

    /**
     * 無効化されたユーザー(issue #816)は、identity-service が 401/403 を返すため
     * {@code IdentityClient#lookupProfile} が {@code Optional.empty()} を返す。
     * これは<b>認証・認可の結果</b>であって障害ではないので(issue #829)、502 ではなく
     * 「操作者なし」として認可で拒否されること(403)を確かめる。
     *
     * <p>この経路は #906 / #583 で「テストが旧メソッドをスタブしたままだったため
     * 検証できていなかった」箇所そのものなので、明示的に足した(issue #916)。
     */
    @Test
    @DisplayName("無効化ユーザー(identityが401/403 → 操作者なし)は403。502にはしない")
    void 操作者を解決できない場合は403() throws Exception {
        when(jwtDecoder.decode("disabled-jwt")).thenReturn(JwtTestFixtures.jwt("sub-disabled", "user"));
        // identity-serviceが401を返す(IntegrationBackendsは未知のトークンを401にする)ので、何も登録しない。

        mockMvc.perform(get(CATEGORIES_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer disabled-jwt"))
                .andExpect(status().isForbidden());

        assertThat(lookups("Bearer disabled-jwt")).isEqualTo(1);
    }
}
