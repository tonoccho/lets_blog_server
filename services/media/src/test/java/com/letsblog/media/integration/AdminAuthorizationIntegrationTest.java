package com.letsblog.media.integration;

import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.media.testsupport.ServiceStubbedIntegrationTest;
import com.letsblog.media.testsupport.ServiceStubs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #644: media-serviceに複製されたAdminAuthorizationService(requireAdminのみ、issue #573)が、
 * 実際のController経由でも401/403を正しく返すことを検証する統合テスト。legacy-apiの
 * {@code AuthorizationMatrixIntegrationTest}と同じ観点((a)JWTが無効なら401、(b)requireAdminは
 * 非adminで403・adminで403にならない)を、{@link
 * com.letsblog.media.controller.ProjectMediaGarbageCollectionController#scan}で検証する。
 *
 * <p>issue #1820: identity-service・publishing-serviceは外部境界として、フェイクHTTPサーバー({@code ServiceStubs})が演じる。
 * 実物の{@code IdentityClient}・{@code MediaGarbageCollectionService}・{@code CmsBridgeClient}がHTTPで通信する。
 * JWTの検証(Keycloakの鍵取得)だけは{@code @MockitoBean}の{@code JwtDecoder}で置き換える。
 */
@DisplayName("media-service: AdminAuthorizationServiceの認可マトリクス統合テスト(issue #644)")
class AdminAuthorizationIntegrationTest extends ServiceStubbedIntegrationTest {

    private static final String SCAN_PATH = "/api/projects/7/media-garbage-collection/scan?environment=local";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Authorizationヘッダーなしは401(issue #772でSecurityConfigの認証ゲートを復元したため、"
            + "コントローラの認可チェック(403)へ到達する前にSpring Securityが弾く)")
    void authorizationヘッダーなしは401() throws Exception {
        mockMvc.perform(get(SCAN_PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("不正なJWTは401(Spring Securityの認証フィルタで弾かれ、コントローラへ到達しない)")
    void 不正なjwtは401() throws Exception {
        when(jwtDecoder.decode("invalid-jwt")).thenThrow(new BadJwtException("invalid token"));

        mockMvc.perform(get(SCAN_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer invalid-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("有効なJWTでもadmin以外は403")
    void admin以外は403() throws Exception {
        when(jwtDecoder.decode("user-jwt")).thenReturn(JwtTestFixtures.jwt("sub-1", "user"));
        ServiceStubs.profile("Bearer user-jwt", 10L, "user");

        mockMvc.perform(get(SCAN_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer user-jwt"))
                .andExpect(status().isForbidden());

        // 操作者解決が実際に lookupProfile を通っていることを確かめる(issue #916)。
        // 呼び出し先が変わってもstubが黙って空振りしないよう、フェイクHTTPサーバーが記録した呼び出しで確かめる(#906 / #583 で実際に起きた)。
        assertThat(ServiceStubs.profileLookups("Bearer user-jwt")).isEqualTo(1);
    }

    @Test
    @DisplayName("adminなら403にならない")
    void adminは403にならない() throws Exception {
        when(jwtDecoder.decode("admin-jwt")).thenReturn(JwtTestFixtures.jwt("sub-2", "admin"));
        ServiceStubs.profile("Bearer admin-jwt", 1L, "admin");
        // 認可を通った先のGCサービスは実物で、publishing-serviceのスキャン結果(メディア0件)をフェイクが返す。
        ServiceStubs.HTTP.json("GET", "/api/internal/publishing/projects/7/media-scan",
                "{\"media\":[],\"refs\":{\"posts\":[],\"settingsMediaIds\":{}}}");

        mockMvc.perform(get(SCAN_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer admin-jwt"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));

        // 操作者解決が実際に lookupProfile を通っていることを確かめる(issue #916)。
        // 呼び出し先が変わってもstubが黙って空振りしないよう、フェイクHTTPサーバーが記録した呼び出しで確かめる(#906 / #583 で実際に起きた)。
        assertThat(ServiceStubs.profileLookups("Bearer admin-jwt")).isEqualTo(1);
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
        ServiceStubs.profileRejected("Bearer disabled-jwt");

        mockMvc.perform(get(SCAN_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer disabled-jwt"))
                .andExpect(status().isForbidden());

        assertThat(ServiceStubs.profileLookups("Bearer disabled-jwt")).isEqualTo(1);
    }
}
