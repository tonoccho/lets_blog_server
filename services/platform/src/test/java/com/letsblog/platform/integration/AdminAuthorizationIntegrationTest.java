package com.letsblog.platform.integration;

import com.letsblog.common.client.ActorProfile;
import java.util.Optional;
import com.letsblog.common.client.IdentityClient;
import com.letsblog.common.testfixtures.JwtTestFixtures;
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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * platform-serviceに複製されたAdminAuthorizationService(requireAdmin/requireAuthenticated)が、
 * 実際のController経由でも401/403を正しく返すことを検証する統合テスト(content-service(#644)/
 * project-serviceの同名テストと同じ観点)。SystemSettingController#setBraveSearchApiKey
 * (admin限定操作)・#getBraveSearchApiKeyStatus(認証済みユーザーであれば可、issue #693の
 * レビュー指摘で追加)で検証する。
 *
 * <p>identity-serviceは外部境界のため、{@link IdentityClient}を{@code @MockitoBean}で置き換える
 * (ADR-0006のモック方針)。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("platform-service: AdminAuthorizationServiceの認可マトリクス統合テスト(issue #693)")
class AdminAuthorizationIntegrationTest {

    private static final String SET_KEY_PATH = "/api/system-settings/brave-search-api-key";
    private static final String REQUEST_BODY = "{\"apiKey\":\"new-key\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private IdentityClient identityClient;

    @Test
    @DisplayName("Authorizationヘッダーなしは401(issue #705でSecurityConfigの認証ゲートを復元したため、"
            + "コントローラ/サービス層のrequireAdmin()による403判定より前に弾かれる)")
    void authorizationヘッダーなしは401() throws Exception {
        mockMvc.perform(put(SET_KEY_PATH).contentType(MediaType.APPLICATION_JSON).content(REQUEST_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("不正なJWTは401(Spring Securityの認証フィルタで弾かれ、コントローラへ到達しない)")
    void 不正なjwtは401() throws Exception {
        when(jwtDecoder.decode("invalid-jwt")).thenThrow(new BadJwtException("invalid token"));

        mockMvc.perform(put(SET_KEY_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("有効なJWTでも非adminなら403")
    void 非adminは403() throws Exception {
        when(jwtDecoder.decode("user-jwt")).thenReturn(JwtTestFixtures.jwt("sub-1", "user"));
        when(identityClient.lookupProfile("Bearer user-jwt"))
                .thenReturn(Optional.of(new ActorProfile(10L, "user")));

        mockMvc.perform(put(SET_KEY_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isForbidden());

        // 操作者解決が実際に lookupProfile を通っていることを確かめる(issue #916)。
        // @MockitoBean は未使用スタブを報告しないため、呼び出し先が変わっても
        // when(...) が黙って空振りする。#906 / #583 で実際に起きた。
        verify(identityClient).lookupProfile("Bearer user-jwt");
    }

    @Test
    @DisplayName("adminなら403にならない")
    void adminは403にならない() throws Exception {
        when(jwtDecoder.decode("admin-jwt")).thenReturn(JwtTestFixtures.jwt("sub-2", "admin"));
        when(identityClient.lookupProfile("Bearer admin-jwt"))
                .thenReturn(Optional.of(new ActorProfile(1L, "admin")));

        mockMvc.perform(put(SET_KEY_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer admin-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isNoContent());

        // 操作者解決が実際に lookupProfile を通っていることを確かめる(issue #916)。
        // @MockitoBean は未使用スタブを報告しないため、呼び出し先が変わっても
        // when(...) が黙って空振りする。#906 / #583 で実際に起きた。
        verify(identityClient).lookupProfile("Bearer admin-jwt");
    }

    @Test
    @DisplayName("GET(状態取得)はAuthorizationヘッダーなしなら401(issue #693のレビュー指摘で"
            + "サービス層のrequireAuthenticated()による403として担保していたが、issue #705で"
            + "SecurityConfig自体の認証ゲートを復元したためコントローラ到達前に401となる)")
    void get_authorizationヘッダーなしは401() throws Exception {
        mockMvc.perform(get(SET_KEY_PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET(状態取得)は有効なJWTさえあればadminでなくても200(admin限定はPUT/DELETEのみ)")
    void get_非adminでも認証済みなら200() throws Exception {
        when(jwtDecoder.decode("user-jwt")).thenReturn(JwtTestFixtures.jwt("sub-3", "user"));
        when(identityClient.lookupProfile("Bearer user-jwt"))
                .thenReturn(Optional.of(new ActorProfile(11L, "user")));

        mockMvc.perform(get(SET_KEY_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer user-jwt"))
                .andExpect(status().isOk());

        // 操作者解決が実際に lookupProfile を通っていることを確かめる(issue #916)。
        // @MockitoBean は未使用スタブを報告しないため、呼び出し先が変わっても
        // when(...) が黙って空振りする。#906 / #583 で実際に起きた。
        verify(identityClient).lookupProfile("Bearer user-jwt");
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
        when(identityClient.lookupProfile("Bearer disabled-jwt")).thenReturn(Optional.empty());

        mockMvc.perform(put(SET_KEY_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer disabled-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isForbidden());

        verify(identityClient).lookupProfile("Bearer disabled-jwt");
    }
    /**
     * ComfyUIの演算デバイス切り替え(issue #1399 AC4)。参照API・適用APIとも、一般ユーザーは
     * Docker Engine APIに触れる前に403で拒否される(Docker Engine APIは未到達のためテストはモック不要)。
     */
    @Test
    @DisplayName("演算デバイス: 一般ユーザーは参照APIも適用APIも403")
    void 演算デバイスは非adminなら参照も適用も403() throws Exception {
        when(jwtDecoder.decode("user-jwt")).thenReturn(JwtTestFixtures.jwt("sub-1399", "user"));
        when(identityClient.lookupProfile("Bearer user-jwt"))
                .thenReturn(Optional.of(new ActorProfile(12L, "user")));

        mockMvc.perform(get("/api/system-settings/compute-devices/comfyui")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-jwt"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/system-settings/compute-devices/comfyui/apply")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"device\":\"GPU\"}"))
                .andExpect(status().isForbidden());
    }
}
