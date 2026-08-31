package com.letsblog.analytics.integration;

import com.letsblog.common.client.IdentityClient;
import com.letsblog.analytics.client.IdentityBridgeClient;
import com.letsblog.common.testfixtures.AuthorizationMatrixContract;
import com.letsblog.common.testfixtures.AuthorizationMatrixContract.Endpoint;
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
 * issue #772: analytics-serviceの認証ゲート(「有効なKeycloak JWTが無ければ401」)の網羅テスト。
 * ADR-0008が必須と定める後退検知手段であり、platform-serviceの同名クラス(#705)をテンプレートに
 * している。{@code SecurityConfig}が{@code permitAll}へ戻れば、このクラスの401アサーションが落ちる。
 *
 * <p>#578でlegacy-apiから移設した{@code ProjectDashboardController}は、legacy-api時代には
 * {@code anyRequest().authenticated()}によって「Authorizationヘッダーが無ければ401」だった。
 * 移設先の{@code SecurityConfig}が他サービスのテンプレート通り全経路{@code permitAll()}だったため、
 * その認証ゲートが失われていた(issue #705と同型の後退)。本クラスはその再発を防ぐ。
 *
 * <p>ロール単位の認可(プロジェクトメンバー/adminの403)は{@link AdminAuthorizationIntegrationTest}が
 * 担当し、ここでは重複させない。
 *
 * <p>identity-service等の外部境界は{@code @MockitoBean}で置き換える(ADR-0006のモック方針)。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("analytics-service: 認証ゲートの認可マトリクス統合テスト(issue #772)")
class AuthorizationMatrixIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private IdentityClient identityClient;

    @MockitoBean
    private IdentityBridgeClient identityBridgeClient;

    /**
     * analytics-serviceがgateway経由で外部へ公開している全エンドポイント
     * ({@code project-dashboard-analytics}ルート: {@code /api/projects/{projectId}/dashboard/**})と、
     * サービス間内部ブリッジ({@code /api/internal/analytics/**}。唯一の呼び出し元である
     * legacy-apiの{@code AnalyticsProjectSettingsClient}はBearerトークンを転送するため
     * 認証必須で問題ない。docs/SYNC_SERVICE_CALLS.md参照)。
     *
     * <p>Authorizationヘッダーの有無だけでSecurityConfigが401を返すため、リクエストボディ/
     * クエリパラメータの妥当性は問わない。
     */
    static Stream<Endpoint> allProtectedEndpoints() {
        return Stream.of(
                // -- ProjectDashboardController --
                new Endpoint("GET", "/api/projects/1/dashboard/google-analytics"),
                new Endpoint("GET", "/api/projects/1/dashboard/adsense"),

                // -- 内部ブリッジ(InternalAnalyticsProjectSettingsController) --
                new Endpoint("GET", "/api/internal/analytics/projects/1/google-analytics"),
                new Endpoint("PUT", "/api/internal/analytics/projects/1/google-analytics"),
                new Endpoint("DELETE", "/api/internal/analytics/projects/1/google-analytics"),
                new Endpoint("GET", "/api/internal/analytics/projects/1/adsense"),
                new Endpoint("PUT", "/api/internal/analytics/projects/1/adsense"),
                new Endpoint("PUT", "/api/internal/analytics/projects/1/adsense/client-secret"),
                new Endpoint("DELETE", "/api/internal/analytics/projects/1/adsense"),
                new Endpoint("POST", "/api/internal/analytics/projects/1/adsense/oauth-callback"),

                // -- ProjectAnalyticsApiKeyController(issue #583でlegacy-apiから移設) --
                new Endpoint("GET", "/api/projects/1/api-keys/google-analytics"),
                new Endpoint("PUT", "/api/projects/1/api-keys/google-analytics"),
                new Endpoint("DELETE", "/api/projects/1/api-keys/google-analytics"),
                new Endpoint("GET", "/api/projects/1/api-keys/adsense"),
                new Endpoint("PUT", "/api/projects/1/api-keys/adsense"),
                new Endpoint("PUT", "/api/projects/1/api-keys/adsense/client-secret"),
                new Endpoint("DELETE", "/api/projects/1/api-keys/adsense"),
                new Endpoint("POST", "/api/projects/1/api-keys/adsense/oauth-callback"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("allProtectedEndpoints")
    @DisplayName("Authorizationヘッダーなしなら例外なく401")
    void everyProtectedEndpoint_returns401WithoutAuthorization(Endpoint endpoint) throws Exception {
        mockMvc.perform(request(HttpMethod.valueOf(endpoint.method()), endpoint.path()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/projects/1/dashboard/google-analytics: 不正なJWTも401")
    void 不正なjwtは401() throws Exception {
        when(jwtDecoder.decode("invalid-jwt")).thenThrow(new BadJwtException("invalid token"));

        mockMvc.perform(request(HttpMethod.GET, "/api/projects/1/dashboard/google-analytics")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("有効なJWTがあれば認証ゲートは通過する(401にならない)")
    void 有効なjwtなら401にならない() throws Exception {
        mockMvc.perform(request(HttpMethod.GET, "/api/projects/1/dashboard/google-analytics")
                        .with(JwtTestFixtures.jwtRequestPostProcessor("sub-772", "user")))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }

    /**
     * docker-composeのhealthcheckとgatewayのDownstreamHealthConfigが無認証で叩くため、公開のまま。
     *
     * <p>404も除外することで「PUBLIC_PATHSに載っているがハンドラが存在しない」状態を検知する
     * (Spring Securityは認証ゲートをハンドラ解決より前に適用するため、401でないことだけでは
     * エンドポイントの存在を保証できない)。200そのものを期待しないのは、テスト環境にRabbitMQ等の
     * 依存が無くヘルス集約の結果がDOWN(503)になりうるためで、ここで検証したいのは
     * 「認証ゲートの対象外であること」だけである。
     */
    @Test
    @DisplayName("Actuatorヘルスチェックは認証ゲートの対象外(401でも404でもない)")
    void actuatorヘルスチェックは401にならない() throws Exception {
        mockMvc.perform(request(HttpMethod.GET, "/actuator/health"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 404));
    }

    /**
     * 一覧とコントローラの実マッピングが一致していることを検証する(issue #805)。
     *
     * <p>「Authorizationヘッダーが無ければ401」というテストは、<b>存在しないパスに対しても通る</b>
     * ため、一覧が陳腐化しても気付けない。#731ではlegacy-apiの一覧に実体の無いパスが107件残っていた。
     * 検証の詳細と限界は{@link AuthorizationMatrixContract}のJavadocを参照。
     */
    @Test
    @DisplayName("エンドポイント一覧がコントローラの実マッピングと一致する(issue #805)")
    void エンドポイント一覧がコントローラと一致する() {
        AuthorizationMatrixContract.verifyMatchesControllers(
                "analytics", allProtectedEndpoints().toList());
    }
}
