package com.letsblog.logwriter.integration;

import com.letsblog.common.client.IdentityClient;
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
 * issue #772: log-writer-serviceの認証ゲート(「有効なKeycloak JWTが無ければ401」)の網羅テスト。
 * ADR-0008が必須と定める後退検知手段であり、platform-serviceの同名クラス(#705)をテンプレートに
 * している。{@code SecurityConfig}が{@code permitAll}へ戻れば、このクラスの401アサーションが落ちる。
 *
 * <p>#572でlegacy-apiから移設した時点では{@code anyRequest().permitAll()}で、「未認証なら403」の
 * 判定は各コントローラー側({@code CurrentActorService}経由)に任されていた。legacy-api時代の
 * {@code anyRequest().authenticated()}によるゲートからの後退であり、issue #772で復元した。
 * 本クラスはその再発を防ぐ。
 *
 * <p>{@code POST /api/logs/errors}は、#772の時点ではブラウザが
 * {@code apps/web/src/lib/errorLogger.ts}から直接叩いておりAuthorizationヘッダーが無いため401になり、
 * フロントエンドのエラーログが無言で全滅していた(legacy-api時代も同じく401だったため
 * ゲートとしては後退ではない)。#791でweb側を是正し、現在はブラウザが同一オリジンのBFF
 * {@code POST /client-errors}を呼び、そこからBearer付きで中継している。
 * 本クラスの401アサーションは、その前提({@code PUBLIC_PATHS}に入れない)を守るためのもの。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("log-writer-service: 認証ゲートの認可マトリクス統合テスト(issue #772)")
class AuthorizationMatrixIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private IdentityClient identityClient;

    /**
     * log-writerがgateway経由で外部へ公開している全エンドポイント(gatewayの{@code log}ルート:
     * {@code /api/logs/**}・{@code /api/audit-logs/**}・{@code /api/operation-logs/**})。
     * 本サービスは{@code /api/internal/**}配下の内部ブリッジを持たず、他サービスからの
     * ログ書き込みはRabbitMQ経由({@code LogMessageListener})でHTTPを通らない。
     *
     * <p>Authorizationヘッダーの有無だけでSecurityConfigが401を返すため、リクエストボディ/
     * クエリパラメータの妥当性は問わない。
     */
    static Stream<Endpoint> allProtectedEndpoints() {
        return Stream.of(
                // -- FrontendErrorLogController --
                new Endpoint("POST", "/api/logs/errors"),
                new Endpoint("GET", "/api/logs/errors"),

                // -- AuditLogController --
                new Endpoint("GET", "/api/audit-logs"),

                // -- OperationLogController --
                new Endpoint("POST", "/api/operation-logs"),
                new Endpoint("GET", "/api/operation-logs"),
                new Endpoint("GET", "/api/operation-logs/op-1"),
                new Endpoint("GET", "/api/operation-logs/unified"),
                new Endpoint("GET", "/api/operation-logs/stats/routes"),
                new Endpoint("GET", "/api/operation-logs/stats/operations"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("allProtectedEndpoints")
    @DisplayName("Authorizationヘッダーなしなら例外なく401")
    void everyProtectedEndpoint_returns401WithoutAuthorization(Endpoint endpoint) throws Exception {
        mockMvc.perform(request(HttpMethod.valueOf(endpoint.method()), endpoint.path()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/audit-logs: 不正なJWTも401")
    void 不正なjwtは401() throws Exception {
        when(jwtDecoder.decode("invalid-jwt")).thenThrow(new BadJwtException("invalid token"));

        mockMvc.perform(request(HttpMethod.GET, "/api/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("有効なJWTがあれば認証ゲートは通過する(401にならない)")
    void 有効なjwtなら401にならない() throws Exception {
        mockMvc.perform(request(HttpMethod.GET, "/api/audit-logs")
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
                "log-writer", allProtectedEndpoints().toList());
    }
}
