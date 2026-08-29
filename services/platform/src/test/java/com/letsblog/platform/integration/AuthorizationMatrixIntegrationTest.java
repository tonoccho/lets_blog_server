package com.letsblog.platform.integration;

import com.letsblog.common.client.IdentityClient;
import com.letsblog.common.testfixtures.JwtTestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #705: platform-serviceの認証ゲート(「有効なKeycloak JWTが無ければ401」)の網羅テスト。
 * legacy-apiの{@code AuthorizationMatrixIntegrationTest#everyProtectedEndpoint_returns401WithoutAuthorization}
 * と同じ観点を、legacy-apiから移設された各コントローラの現在の所有者であるplatform-serviceで再現する。
 *
 * <p>#693/#694/#695/#696の移設時、本サービスの{@code SecurityConfig}が他サービスのテンプレート通り
 * 全経路{@code permitAll()}だったため、legacy-api時代には401だった
 * {@code GET /api/system/vscode-extension}等がAuthorizationヘッダー無しでも200を返す後退が
 * 発生していた。本クラスはその再発を防ぐ。
 *
 * <p>ロール単位の認可(admin限定操作の403)は{@link AdminAuthorizationIntegrationTest}が担当し、
 * ここでは重複させない。
 *
 * <p>identity-serviceは外部境界のため{@link IdentityClient}を{@code @MockitoBean}で置き換える
 * (ADR-0006のモック方針)。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("platform-service: 認証ゲートの認可マトリクス統合テスト(issue #705)")
class AuthorizationMatrixIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private IdentityClient identityClient;

    record Endpoint(String method, String path) {
        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    /**
     * platform-serviceがgateway経由で外部へ公開している全エンドポイント
     * (gatewayの{@code platform}ルート: {@code /api/system/**}・{@code /api/backup/**}・
     * {@code /api/system-settings/**}、および{@code dashboard-status}ルート:
     * {@code /api/dashboard/**})。
     *
     * <p>Authorizationヘッダーの有無だけでSecurityConfigが401を返すため、リクエストボディ/
     * クエリパラメータの妥当性は問わない。
     */
    static Stream<Endpoint> allProtectedEndpoints() {
        return Stream.of(
                // -- VscodeExtensionController (1、issue #696) --
                new Endpoint("GET", "/api/system/vscode-extension"),

                // -- BackupController (2、issue #694) --
                new Endpoint("GET", "/api/backup/download"),
                new Endpoint("POST", "/api/backup/restore"),

                // -- SystemSettingController (3、issue #693) --
                new Endpoint("GET", "/api/system-settings/brave-search-api-key"),
                new Endpoint("PUT", "/api/system-settings/brave-search-api-key"),
                new Endpoint("DELETE", "/api/system-settings/brave-search-api-key"),

                // -- AppSettingController (2、issue #693) --
                new Endpoint("GET", "/api/system-settings/app-settings"),
                new Endpoint("PUT", "/api/system-settings/app-settings"),

                // -- DashboardController (5、issue #695) --
                new Endpoint("GET", "/api/dashboard/service-status"),
                new Endpoint("GET", "/api/dashboard/service-status/stream"),
                new Endpoint("GET", "/api/dashboard/service-status/detail"),
                new Endpoint("GET", "/api/dashboard/container-status"),
                new Endpoint("GET", "/api/dashboard/container-status/stream"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("allProtectedEndpoints")
    @DisplayName("Authorizationヘッダーなしなら例外なく401")
    void everyProtectedEndpoint_returns401WithoutAuthorization(Endpoint endpoint) throws Exception {
        mockMvc.perform(request(HttpMethod.valueOf(endpoint.method()), endpoint.path()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/system/vscode-extension: 不正なJWTも401(issue #705の中心的な回帰)")
    void vscodeExtension_不正なjwtは401() throws Exception {
        when(jwtDecoder.decode("invalid-jwt")).thenThrow(new BadJwtException("invalid token"));

        mockMvc.perform(request(HttpMethod.GET, "/api/system/vscode-extension")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("有効なJWTがあれば認証ゲートは通過する(401にならない)")
    void 有効なjwtなら401にならない() throws Exception {
        mockMvc.perform(request(HttpMethod.GET, "/api/dashboard/container-status")
                        .with(JwtTestFixtures.jwtRequestPostProcessor("sub-705", "user")))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }

    /**
     * サービス間内部ブリッジ({@code /api/internal/platform/**})は、gatewayのルート表に載っておらず
     * 外部から到達できない一方、唯一の呼び出し元であるlegacy-apiの{@code PlatformServiceClient}が
     * Bearerトークンを転送しないため、SecurityConfigのPUBLIC_PATHSに残している。認証必須化に巻き込んで
     * サービス間呼び出しを壊していないことを確認する(SecurityConfigのJavadoc参照)。
     */
    @Test
    @DisplayName("内部ブリッジ /api/internal/platform/** は認証ゲートの対象外(401にならない)")
    void 内部ブリッジは401にならない() throws Exception {
        mockMvc.perform(request(HttpMethod.GET, "/api/internal/platform/system-settings/brave-search-api-key"))
                .andExpect(status().isOk());
    }

    /** docker-composeのhealthcheckとgatewayのDownstreamHealthConfigが無認証で叩くため、公開のまま。 */
    @Test
    @DisplayName("Actuatorヘルスチェックは認証ゲートの対象外")
    void actuatorヘルスチェックは401にならない() throws Exception {
        mockMvc.perform(request(HttpMethod.GET, "/actuator/health"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }
}
