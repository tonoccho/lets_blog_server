package com.letsblog.identity.integration;

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
 * issue #772: identity-serviceの認証ゲート(「有効なKeycloak JWTが無ければ401」)の網羅テスト。
 * ADR-0008が必須と定める後退検知手段であり、platform-serviceの同名クラス(#705)をテンプレートに
 * している。{@code SecurityConfig}が{@code permitAll}へ戻れば、このクラスの401アサーションが落ちる。
 *
 * <p>#563の時点では{@code anyRequest().permitAll()}で、認可判定は各コントローラの
 * {@code AdminAuthorizationService}/{@code PermissionAuthorizationService}に任されていた。
 * legacy-api時代の{@code anyRequest().authenticated()}によるゲートからの後退であり、
 * issue #772でSecurityConfigレベルの一律ゲートを復元した。本クラスはその再発を防ぐ。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("identity-service: 認証ゲートの認可マトリクス統合テスト(issue #772)")
class AuthorizationMatrixIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    /**
     * identity-serviceがgateway経由で外部へ公開している全エンドポイント(gatewayの{@code identity}
     * ルート: {@code /api/identity/**}・{@code /api/users/**}・{@code /api/roles/**})。
     * 本サービスは{@code /api/internal/**}配下の内部ブリッジを持たない。
     *
     * <p>Authorizationヘッダーの有無だけでSecurityConfigが401を返すため、リクエストボディ/
     * クエリパラメータの妥当性は問わない。
     */
    static Stream<Endpoint> allProtectedEndpoints() {
        return Stream.of(
                // -- IdentityController --
                new Endpoint("GET", "/api/identity/me"),
                new Endpoint("GET", "/api/identity/me/permissions"),
                new Endpoint("PATCH", "/api/identity/me/preferences"),
                new Endpoint("GET", "/api/identity/users/1/permissions"),

                // -- UserController --
                new Endpoint("GET", "/api/users"),
                new Endpoint("POST", "/api/users"),
                new Endpoint("GET", "/api/users/1"),
                new Endpoint("PUT", "/api/users/1"),
                new Endpoint("PATCH", "/api/users/1"),
                new Endpoint("DELETE", "/api/users/1"),
                new Endpoint("POST", "/api/users/1/deactivate"),
                new Endpoint("POST", "/api/users/1/reactivate"),
                new Endpoint("POST", "/api/users/migrate-to-keycloak"),
                new Endpoint("POST", "/api/users/reconcile-keycloak"),
                new Endpoint("PATCH", "/api/users/1/preferences"),
                new Endpoint("PUT", "/api/users/1/github-token"),
                new Endpoint("POST", "/api/users/1/roles/admin"),
                new Endpoint("DELETE", "/api/users/1/roles/admin"),
                // -- AvatarController(issue #1241) --
                new Endpoint("POST", "/api/users/1/avatar"),
                new Endpoint("GET", "/api/users/1/avatar"),

                // -- RoleController --
                new Endpoint("GET", "/api/roles"),

                // -- ProjectUserController(issue #583でlegacy-apiから移設) --
                new Endpoint("GET", "/api/projects/1/users"),
                new Endpoint("POST", "/api/projects/1/users"),
                new Endpoint("PUT", "/api/projects/1/users/1"),
                new Endpoint("DELETE", "/api/projects/1/users/1"),
                // issue #1242: メンバー個別のユーザー情報再同期
                new Endpoint("POST", "/api/projects/1/users/1/sync"),
                new Endpoint("GET", "/api/project-users"),

                // -- 内部ブリッジ(InternalProjectUserController、issue #583) --
                new Endpoint("GET", "/api/internal/identity/projects/1/members/1"),
                new Endpoint("GET", "/api/internal/identity/users/1/project-ids"),
                new Endpoint("GET", "/api/internal/identity/roles"),
                new Endpoint("GET", "/api/internal/identity/user-site-authors/1/1"),
                new Endpoint("POST", "/api/internal/identity/user-site-authors"),
                new Endpoint("POST", "/api/internal/identity/project-users/1/sites/1/reconcile-roles"),
                new Endpoint("GET", "/api/internal/identity/users/1/github-token"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("allProtectedEndpoints")
    @DisplayName("Authorizationヘッダーなしなら例外なく401")
    void everyProtectedEndpoint_returns401WithoutAuthorization(Endpoint endpoint) throws Exception {
        mockMvc.perform(request(HttpMethod.valueOf(endpoint.method()), endpoint.path()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/identity/me: 不正なJWTも401")
    void 不正なjwtは401() throws Exception {
        when(jwtDecoder.decode("invalid-jwt")).thenThrow(new BadJwtException("invalid token"));

        mockMvc.perform(request(HttpMethod.GET, "/api/identity/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("有効なJWTがあれば認証ゲートは通過する(401にならない)")
    void 有効なjwtなら401にならない() throws Exception {
        mockMvc.perform(request(HttpMethod.GET, "/api/identity/me")
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
                "identity", allProtectedEndpoints().toList());
    }
}
