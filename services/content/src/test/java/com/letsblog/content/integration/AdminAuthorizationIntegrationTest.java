package com.letsblog.content.integration;

import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.content.testsupport.ContentIntegrationTestBase;
import com.letsblog.content.testsupport.ExternalServices;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #644: content-serviceに複製されたAdminAuthorizationService(requireProjectMemberOrAdmin、
 * issue #576)が、実際のController経由でも401/403を正しく返すことを検証する統合テスト。
 * legacy-apiの{@code AuthorizationMatrixIntegrationTest}と同じ観点((a)JWTが無効なら401、
 * (b)/(c)admin/プロジェクトメンバーの認可判定は403で表現される)を、content-serviceの認可経路
 * (JWTのsubからではなく、CurrentActorServiceがAuthorizationヘッダーをidentity-serviceへ転送して
 * 解決する。IdentityBridgeClient経由のプロジェクトメンバー判定)に合わせて{@link
 * com.letsblog.content.controller.ArticlePreviewController#render}で検証する。
 *
 * <p>issue #1822: {@code IdentityClient}/{@code IdentityBridgeClient}/{@code ProjectBridgeClient}は実物のBeanで、
 * identity-service/project-serviceのHTTPを{@link ExternalServices}(サーバー側フェイク)で受ける。
 * 外部との通信でないものはモックしない(JWTの公開鍵取得を伴う{@code JwtDecoder}だけがMockito)。
 */
@DisplayName("content-service: AdminAuthorizationServiceの認可マトリクス統合テスト(issue #644)")
class AdminAuthorizationIntegrationTest extends ContentIntegrationTestBase {

    private static final String RENDER_PATH = "/api/projects/42/preview/render";
    private static final String RENDER_BODY = "{\"markdown\":\"# hello\"}";

    @Autowired
    private MockMvc mockMvc;

    /** 操作者解決が実際にidentity-serviceの/api/identity/meを通り、そのAuthorizationを転送していること(issue #916)。 */
    private static void assertLookedUp(String authorization) {
        assertThat(ExternalServices.identityLookups())
                .extracting(request -> request.header("Authorization"))
                .contains(authorization);
    }

    @Test
    @DisplayName("Authorizationヘッダーなしは401(issue #772でSecurityConfigの認証ゲートを復元したため、"
            + "コントローラの認可チェック(403)へ到達する前にSpring Securityが弾く)")
    void authorizationヘッダーなしは401() throws Exception {
        mockMvc.perform(post(RENDER_PATH).contentType(MediaType.APPLICATION_JSON).content(RENDER_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("不正なJWTは401(Spring Securityの認証フィルタで弾かれ、コントローラへ到達しない)")
    void 不正なjwtは401() throws Exception {
        when(jwtDecoder.decode("invalid-jwt")).thenThrow(new BadJwtException("invalid token"));

        mockMvc.perform(post(RENDER_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RENDER_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("有効なJWTでも非member・非adminなら403")
    void 非メンバー非adminは403() throws Exception {
        when(jwtDecoder.decode("member-check-jwt")).thenReturn(JwtTestFixtures.jwt("sub-1", "user"));
        ExternalServices.actor("Bearer member-check-jwt", 10L, "user");
        ExternalServices.projectMember(42L, 10L, false);

        mockMvc.perform(post(RENDER_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer member-check-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RENDER_BODY))
                .andExpect(status().isForbidden());

        // 操作者解決が実際にidentity-serviceへ届いていることを確かめる(issue #916)。
        assertLookedUp("Bearer member-check-jwt");
    }

    @Test
    @DisplayName("プロジェクトメンバーなら403にならない")
    void プロジェクトメンバーは403にならない() throws Exception {
        when(jwtDecoder.decode("member-jwt")).thenReturn(JwtTestFixtures.jwt("sub-2", "user"));
        ExternalServices.actor("Bearer member-jwt", 11L, "user");
        ExternalServices.projectMember(42L, 11L, true);

        mockMvc.perform(post(RENDER_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer member-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RENDER_BODY))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));

        // 操作者解決が実際にidentity-serviceへ届いていることを確かめる(issue #916)。
        assertLookedUp("Bearer member-jwt");
    }

    @Test
    @DisplayName("adminなら所属に関わらず403にならない(プロジェクトメンバー判定はバイパスされる)")
    void adminは403にならない() throws Exception {
        when(jwtDecoder.decode("admin-jwt")).thenReturn(JwtTestFixtures.jwt("sub-3", "admin"));
        ExternalServices.actor("Bearer admin-jwt", 1L, "admin");

        mockMvc.perform(post(RENDER_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer admin-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RENDER_BODY))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));

        // 操作者解決が実際にidentity-serviceへ届いていることを確かめる(issue #916)。
        assertLookedUp("Bearer admin-jwt");
    }

    /**
     * 無効化されたユーザー(issue #816)は、identity-service が 401/403 を返すため
     * {@code IdentityClient#lookupProfile} が {@link java.util.Optional#empty()} を返す。
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
        ExternalServices.disabledActor("Bearer disabled-jwt");

        mockMvc.perform(post(RENDER_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer disabled-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RENDER_BODY))
                .andExpect(status().isForbidden());

        assertLookedUp("Bearer disabled-jwt");
    }
}
