package com.letsblog.analytics.integration;

import com.letsblog.common.testfixtures.JwtTestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.BadJwtException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #644: analytics-serviceに複製されたAdminAuthorizationService(requireProjectMemberOrAdmin、
 * issue #578)が、実際のController経由でも401/403を正しく返すことを検証する統合テスト。legacy-apiの
 * {@code AuthorizationMatrixIntegrationTest}と同じ観点((a)JWTが無効なら401、(b)/(c)admin/
 * プロジェクトメンバーの認可判定は403で表現される)を、analytics-serviceの認可経路
 * (GoogleAnalyticsReportServiceが呼び出し冒頭でrequireProjectMemberOrAdminを行う)に合わせて
 * {@link com.letsblog.analytics.controller.ProjectDashboardController#getGoogleAnalyticsReport}で
 * 検証する。
 *
 * <p>issue #1827: {@code IdentityClient}・{@code IdentityBridgeClient}・{@code ProjectBridgeClient}は実物のBeanで、
 * 外部との通信である identity-service・project-service を{@link AnalyticsIntegrationTestBase}のサーバー側フェイクの
 * HTTPで差し替える(JWTの検証だけは Keycloak の鍵取得を伴うため{@code JwtDecoder}を{@code @MockitoBean}にする)。
 * 「操作者の解決が実際に identity-service の {@code /api/identity/me} を通っている」ことは、フェイクが受けた
 * 要求で確かめる(issue #916)。
 */
@DisplayName("analytics-service: AdminAuthorizationServiceの認可マトリクス統合テスト(issue #644)")
class AdminAuthorizationIntegrationTest extends AnalyticsIntegrationTestBase {

    private static final String GA_REPORT_PATH = "/api/projects/42/dashboard/google-analytics";

    @Test
    @DisplayName("Authorizationヘッダーなしは401(issue #772でSecurityConfigの認証ゲートを復元したため、"
            + "コントローラの認可チェック(403)へ到達する前にSpring Securityが弾く)")
    void authorizationヘッダーなしは401() throws Exception {
        mockMvc.perform(get(GA_REPORT_PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("不正なJWTは401(Spring Securityの認証フィルタで弾かれ、コントローラへ到達しない)")
    void 不正なjwtは401() throws Exception {
        when(jwtDecoder.decode("invalid-jwt")).thenThrow(new BadJwtException("invalid token"));

        mockMvc.perform(get(GA_REPORT_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer invalid-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("有効なJWTでも非member・非adminなら403")
    void 非メンバー非adminは403() throws Exception {
        when(jwtDecoder.decode("member-check-jwt")).thenReturn(JwtTestFixtures.jwt("sub-1", "user"));
        BACKENDS.actor("member-check-jwt", 10L, "user");

        mockMvc.perform(get(GA_REPORT_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer member-check-jwt"))
                .andExpect(status().isForbidden());

        // 操作者解決が実際に lookupProfile を通っていることを確かめる(issue #916)。
        // 呼び出し先が変わっても気付けるように、フェイクが受けた要求で確かめる(#906 / #583 で実際に起きた)。
        assertThat(HTTP.requests("GET", "/api/identity/me")).hasSize(1);
        assertThat(HTTP.requests("GET", "/api/identity/me").get(0).header("Authorization")).isEqualTo("Bearer member-check-jwt");
    }

    @Test
    @DisplayName("プロジェクトメンバーなら403にならない")
    void プロジェクトメンバーは403にならない() throws Exception {
        when(jwtDecoder.decode("member-jwt")).thenReturn(JwtTestFixtures.jwt("sub-2", "user"));
        BACKENDS.actor("member-jwt", 11L, "user").member(42L, 11L).project(42L, true);

        mockMvc.perform(get(GA_REPORT_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer member-jwt"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));

        // 操作者解決が実際に lookupProfile を通っていることを確かめる(issue #916)。
        // 呼び出し先が変わっても気付けるように、フェイクが受けた要求で確かめる(#906 / #583 で実際に起きた)。
        assertThat(HTTP.requests("GET", "/api/identity/me")).hasSize(1);
        assertThat(HTTP.requests("GET", "/api/identity/me").get(0).header("Authorization")).isEqualTo("Bearer member-jwt");
    }

    @Test
    @DisplayName("adminなら所属に関わらず403にならない(プロジェクトメンバー判定はバイパスされる)")
    void adminは403にならない() throws Exception {
        when(jwtDecoder.decode("admin-jwt")).thenReturn(JwtTestFixtures.jwt("sub-3", "admin"));
        BACKENDS.actor("admin-jwt", 1L, "admin").project(42L, true);

        mockMvc.perform(get(GA_REPORT_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer admin-jwt"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));

        // 操作者解決が実際に lookupProfile を通っていることを確かめる(issue #916)。
        // 呼び出し先が変わっても気付けるように、フェイクが受けた要求で確かめる(#906 / #583 で実際に起きた)。
        assertThat(HTTP.requests("GET", "/api/identity/me")).hasSize(1);
        assertThat(HTTP.requests("GET", "/api/identity/me").get(0).header("Authorization")).isEqualTo("Bearer admin-jwt");
    }

    /**
     * 無効化されたユーザー(issue #816)は、identity-service が 401/403 を返すため
     * {@code IdentityClient#lookupProfile} が {@link Optional#empty()} を返す。
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
        // BACKENDS に登録の無いトークンは identity-service が401を返す(= 操作者なし)。

        mockMvc.perform(get(GA_REPORT_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer disabled-jwt"))
                .andExpect(status().isForbidden());

        assertThat(HTTP.requests("GET", "/api/identity/me")).hasSize(1);
        assertThat(HTTP.requests("GET", "/api/identity/me").get(0).header("Authorization")).isEqualTo("Bearer disabled-jwt");
    }
}
