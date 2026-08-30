package com.letsblog.content.integration;

import com.letsblog.common.client.ActorProfile;
import com.letsblog.common.client.IdentityClient;
import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.content.client.LegacyApiBridgeClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #644: content-serviceに複製されたAdminAuthorizationService(requireProjectMemberOrAdmin、
 * issue #576)が、実際のController経由でも401/403を正しく返すことを検証する統合テスト。
 * legacy-apiの{@code AuthorizationMatrixIntegrationTest}と同じ観点((a)JWTが無効なら401、
 * (b)/(c)admin/プロジェクトメンバーの認可判定は403で表現される)を、content-serviceの認可経路
 * (JWTのsubからではなく、CurrentActorServiceがAuthorizationヘッダーをidentity-serviceへ転送して
 * 解決する。LegacyApiBridgeClient経由のプロジェクトメンバー判定)に合わせて{@link
 * com.letsblog.content.controller.ArticlePreviewController#render}で検証する。
 *
 * <p>identity-service/legacy-apiは外部境界のため、legacy-apiのテストと同様に{@link IdentityClient}/
 * {@link LegacyApiBridgeClient}を{@code @MockitoBean}で置き換える(ADR-0006のモック方針、
 * 実際のサービス間HTTP呼び出しはまだWireMock導入前のため)。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("content-service: AdminAuthorizationServiceの認可マトリクス統合テスト(issue #644)")
class AdminAuthorizationIntegrationTest {

    private static final String RENDER_PATH = "/api/projects/42/preview/render";
    private static final String RENDER_BODY = "{\"markdown\":\"# hello\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private IdentityClient identityClient;

    @MockitoBean
    private LegacyApiBridgeClient legacyApiBridgeClient;

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
        when(identityClient.fetchProfile("Bearer member-check-jwt")).thenReturn(new ActorProfile(10L, "user"));
        when(legacyApiBridgeClient.isProjectMember(42L, 10L, "Bearer member-check-jwt")).thenReturn(false);

        mockMvc.perform(post(RENDER_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer member-check-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RENDER_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("プロジェクトメンバーなら403にならない")
    void プロジェクトメンバーは403にならない() throws Exception {
        when(jwtDecoder.decode("member-jwt")).thenReturn(JwtTestFixtures.jwt("sub-2", "user"));
        when(identityClient.fetchProfile("Bearer member-jwt")).thenReturn(new ActorProfile(11L, "user"));
        when(legacyApiBridgeClient.isProjectMember(42L, 11L, "Bearer member-jwt")).thenReturn(true);
        // 認可通過後、目次(TOC)のカスタムHTMLテンプレート取得(TocStyleRenderService)もlegacy-apiへの
        // 内部ブリッジを経由するため、認可の検証対象ではないがnullを返さないようスタブしておく
        // (htmlTemplate()がnullなら未カスタマイズとしてそのまま返す実装だが、レスポンス自体はnullを
        // 許容しないため)。
        when(legacyApiBridgeClient.resolveTagDesign(anyLong(), anyString(), anyString()))
                .thenReturn(new LegacyApiBridgeClient.TagDesignResponse(null, null, null, null, null));

        mockMvc.perform(post(RENDER_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer member-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RENDER_BODY))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
    }

    @Test
    @DisplayName("adminなら所属に関わらず403にならない(プロジェクトメンバー判定はバイパスされる)")
    void adminは403にならない() throws Exception {
        when(jwtDecoder.decode("admin-jwt")).thenReturn(JwtTestFixtures.jwt("sub-3", "admin"));
        when(identityClient.fetchProfile("Bearer admin-jwt")).thenReturn(new ActorProfile(1L, "admin"));
        when(legacyApiBridgeClient.resolveTagDesign(anyLong(), anyString(), anyString()))
                .thenReturn(new LegacyApiBridgeClient.TagDesignResponse(null, null, null, null, null));

        mockMvc.perform(post(RENDER_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer admin-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RENDER_BODY))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
    }
}
