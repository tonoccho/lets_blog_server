package com.letsblog.media.integration;

import com.letsblog.common.client.ActorProfile;
import com.letsblog.common.client.IdentityClient;
import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.media.dto.MediaGarbageCollectionScanResponse;
import com.letsblog.media.service.MediaGarbageCollectionService;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
 * <p>identity-serviceは外部境界のため{@link IdentityClient}を{@code @MockitoBean}で置き換える
 * (ADR-0006のモック方針)。認可通過後に呼ばれる{@link MediaGarbageCollectionService}は、legacy-api側の
 * CMSブリッジへ実際にHTTP接続する実装のため、認可判定そのものとは無関係な結合を避ける目的で
 * 併せてモックする。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("media-service: AdminAuthorizationServiceの認可マトリクス統合テスト(issue #644)")
class AdminAuthorizationIntegrationTest {

    private static final String SCAN_PATH = "/api/projects/7/media-garbage-collection/scan?environment=local";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private IdentityClient identityClient;

    @MockitoBean
    private MediaGarbageCollectionService mediaGarbageCollectionService;

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
        when(identityClient.fetchProfile("Bearer user-jwt")).thenReturn(new ActorProfile(10L, "user"));

        mockMvc.perform(get(SCAN_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer user-jwt"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("adminなら403にならない")
    void adminは403にならない() throws Exception {
        when(jwtDecoder.decode("admin-jwt")).thenReturn(JwtTestFixtures.jwt("sub-2", "admin"));
        when(identityClient.fetchProfile("Bearer admin-jwt")).thenReturn(new ActorProfile(1L, "admin"));
        when(mediaGarbageCollectionService.scan(eq(7L), anyString(), anyString()))
                .thenReturn(new MediaGarbageCollectionScanResponse("local", List.of(), 0, 0, 0));

        mockMvc.perform(get(SCAN_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer admin-jwt"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
    }
}
