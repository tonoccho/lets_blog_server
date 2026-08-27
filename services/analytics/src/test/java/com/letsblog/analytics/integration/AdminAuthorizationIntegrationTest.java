package com.letsblog.analytics.integration;

import com.letsblog.analytics.client.LegacyApiBridgeClient;
import com.letsblog.common.client.ActorProfile;
import com.letsblog.common.client.IdentityClient;
import com.letsblog.common.testfixtures.JwtTestFixtures;
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
 * issue #644: analytics-serviceに複製されたAdminAuthorizationService(requireProjectMemberOrAdmin、
 * issue #578)が、実際のController経由でも401/403を正しく返すことを検証する統合テスト。legacy-apiの
 * {@code AuthorizationMatrixIntegrationTest}と同じ観点((a)JWTが無効なら401、(b)/(c)admin/
 * プロジェクトメンバーの認可判定は403で表現される)を、analytics-serviceの認可経路
 * (GoogleAnalyticsReportServiceが呼び出し冒頭でrequireProjectMemberOrAdminを行う)に合わせて
 * {@link com.letsblog.analytics.controller.ProjectDashboardController#getGoogleAnalyticsReport}で
 * 検証する。
 *
 * <p>identity-serviceは外部境界のため{@link IdentityClient}を{@code @MockitoBean}で置き換える
 * (ADR-0006のモック方針)。{@link LegacyApiBridgeClient#isProjectMember}は非member/admin判定に
 * 必要なためモックする。認可通過後に呼ばれる{@code getProjectEligibility}は、legacy-apiが
 * テスト環境で稼働していないため接続失敗(502)にはなるが、403にはならないため「403にならない」
 * ケースの検証には影響しない。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("analytics-service: AdminAuthorizationServiceの認可マトリクス統合テスト(issue #644)")
class AdminAuthorizationIntegrationTest {

    private static final String GA_REPORT_PATH = "/api/projects/42/dashboard/google-analytics";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private IdentityClient identityClient;

    @MockitoBean
    private LegacyApiBridgeClient legacyApiBridgeClient;

    @Test
    @DisplayName("Authorizationヘッダーなしは非adminとみなされ403(プロジェクトメンバーでも管理者でもない)")
    void authorizationヘッダーなしは403() throws Exception {
        mockMvc.perform(get(GA_REPORT_PATH)).andExpect(status().isForbidden());
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
        when(identityClient.fetchProfile("Bearer member-check-jwt")).thenReturn(new ActorProfile(10L, "user"));
        when(legacyApiBridgeClient.isProjectMember(42L, 10L, "Bearer member-check-jwt")).thenReturn(false);

        mockMvc.perform(get(GA_REPORT_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer member-check-jwt"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("プロジェクトメンバーなら403にならない")
    void プロジェクトメンバーは403にならない() throws Exception {
        when(jwtDecoder.decode("member-jwt")).thenReturn(JwtTestFixtures.jwt("sub-2", "user"));
        when(identityClient.fetchProfile("Bearer member-jwt")).thenReturn(new ActorProfile(11L, "user"));
        when(legacyApiBridgeClient.isProjectMember(42L, 11L, "Bearer member-jwt")).thenReturn(true);

        mockMvc.perform(get(GA_REPORT_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer member-jwt"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
    }

    @Test
    @DisplayName("adminなら所属に関わらず403にならない(プロジェクトメンバー判定はバイパスされる)")
    void adminは403にならない() throws Exception {
        when(jwtDecoder.decode("admin-jwt")).thenReturn(JwtTestFixtures.jwt("sub-3", "admin"));
        when(identityClient.fetchProfile("Bearer admin-jwt")).thenReturn(new ActorProfile(1L, "admin"));

        mockMvc.perform(get(GA_REPORT_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer admin-jwt"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
    }
}
