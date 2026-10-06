package com.letsblog.project.integration;

import com.letsblog.common.testfixtures.AuthorizationMatrixContract;
import com.letsblog.common.testfixtures.AuthorizationMatrixContract.Endpoint;
import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.project.client.IdentityClient;
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
 * issue #772: project-serviceの認証ゲート(「有効なKeycloak JWTが無ければ401」)の網羅テスト。
 * ADR-0008が必須と定める後退検知手段であり、platform-serviceの同名クラス(#705)をテンプレートに
 * している。{@code SecurityConfig}が{@code permitAll}へ戻れば、このクラスの401アサーションが落ちる。
 *
 * <p>#577でlegacy-apiから移設した時点では{@code /api/internal/**}のみ{@code authenticated()}で、
 * 残りは{@code permitAll()}だった。そのため{@code GET /api/projects}が未認証でプロジェクト名・
 * スラッグ等の実データを返していた(issue #772の再現手順そのもの)。本クラスはその再発を防ぐ。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("project-service: 認証ゲートの認可マトリクス統合テスト(issue #772)")
class AuthorizationMatrixIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private IdentityClient identityClient;

    /**
     * project-serviceがgateway経由で外部へ公開している全エンドポイント(gatewayの{@code project}
     * ルート: {@code /api/projects/**}・{@code /api/sites/**}、{@code project-ssh-key-pairs}・
     * {@code project-tag-design-settings}・{@code project-environment-sync}ルート)と、
     * サービス間内部ブリッジ({@code /api/internal/project/**}。#577時点から認証必須で、
     * 呼び出し元はBearerトークンを転送する。docs/SYNC_SERVICE_CALLS.md参照)。
     *
     * <p>Authorizationヘッダーの有無だけでSecurityConfigが401を返すため、リクエストボディ/
     * クエリパラメータの妥当性は問わない。
     */
    static Stream<Endpoint> allProtectedEndpoints() {
        return Stream.of(
                // -- ProjectController --
                new Endpoint("POST", "/api/projects"),
                new Endpoint("GET", "/api/projects"),
                new Endpoint("GET", "/api/projects/1"),
                new Endpoint("PUT", "/api/projects/1"),
                new Endpoint("DELETE", "/api/projects/1"),
                new Endpoint("POST", "/api/projects/1/environments"),
                new Endpoint("DELETE", "/api/projects/1/environments/production"),
                new Endpoint("PUT", "/api/projects/1/master-environment"),
                new Endpoint("PUT", "/api/projects/1/github-repository"),
                new Endpoint("POST", "/api/projects/1/environments/sync"),

                // -- ProjectSnsController(issue #1574) --
                new Endpoint("GET", "/api/projects/1/sns/x"),
                new Endpoint("POST", "/api/projects/1/sns/x/authorize"),
                new Endpoint("POST", "/api/projects/1/sns/x/callback"),
                new Endpoint("POST", "/api/projects/1/sns/x/test"),

                // -- ProjectSnsThreadsController(issue #1579) --
                new Endpoint("GET", "/api/projects/1/sns/threads"),
                new Endpoint("POST", "/api/projects/1/sns/threads/authorize"),
                new Endpoint("POST", "/api/projects/1/sns/threads/callback"),
                new Endpoint("POST", "/api/projects/1/sns/threads/test"),
                new Endpoint("DELETE", "/api/projects/1/sns/threads"),

                // -- ProjectSnsFacebookController(issue #1580) --
                new Endpoint("GET", "/api/projects/1/sns/facebook"),
                new Endpoint("POST", "/api/projects/1/sns/facebook/authorize"),
                new Endpoint("POST", "/api/projects/1/sns/facebook/callback"),
                new Endpoint("GET", "/api/projects/1/sns/facebook/pages"),
                new Endpoint("POST", "/api/projects/1/sns/facebook/page"),
                new Endpoint("POST", "/api/projects/1/sns/facebook/test"),
                new Endpoint("DELETE", "/api/projects/1/sns/facebook"),

                // -- ProjectSnsPvController(issue #1578) --
                new Endpoint("GET", "/api/projects/1/sns/pv"),
                new Endpoint("POST", "/api/projects/1/sns/pv/rules"),
                new Endpoint("DELETE", "/api/projects/1/sns/pv/rules/r1"),
                new Endpoint("POST", "/api/projects/1/sns/pv/resend"),

                // -- ProjectSnsTemplateController(issue #1583) --
                new Endpoint("GET", "/api/projects/1/sns/templates"),
                new Endpoint("PUT", "/api/projects/1/sns/templates"),
                new Endpoint("POST", "/api/projects/1/sns/templates/resend"),

                // -- SiteController --
                new Endpoint("POST", "/api/sites"),
                new Endpoint("POST", "/api/sites/managed-wordpress"),
                new Endpoint("POST", "/api/sites/managed-wordpress/jobs"),
                new Endpoint("POST", "/api/sites/managed-wordpress/adopt"),
                new Endpoint("GET", "/api/sites"),
                new Endpoint("GET", "/api/sites/1"),
                new Endpoint("POST", "/api/sites/ssh-keypair"),
                new Endpoint("PUT", "/api/sites/1"),
                new Endpoint("POST", "/api/sites/1/test-connection"),
                new Endpoint("DELETE", "/api/sites/1"),
                new Endpoint("POST", "/api/sites/1/install-wp-cli"),
                new Endpoint("GET", "/api/sites/1/letsblog-plugin"),
                new Endpoint("POST", "/api/sites/1/letsblog-plugin/install"),
                new Endpoint("GET", "/api/sites/1/letsblog-sync"),
                new Endpoint("POST", "/api/sites/1/letsblog-sync"),
                new Endpoint("POST", "/api/sites/1/reprovision"),

                // -- SiteStaticContentController --
                new Endpoint("GET", "/api/sites/1/static-content"),
                new Endpoint("POST", "/api/sites/1/static-content/generate"),

                // -- SshKeyPairController --
                new Endpoint("GET", "/api/ssh-key-pairs"),
                new Endpoint("POST", "/api/ssh-key-pairs"),
                new Endpoint("DELETE", "/api/ssh-key-pairs/1"),

                // -- TagDesignSettingController --
                new Endpoint("GET", "/api/projects/1/tag-design-settings"),
                new Endpoint("PUT", "/api/projects/1/tag-design-settings/plantuml"),
                new Endpoint("POST", "/api/projects/1/tag-design-settings/plantuml/generate"),
                // プロジェクト未紐付けサイト向けのグローバル既定タグデザイン(issue #763)。
                // GlobalTagDesignSettingController。認可はadmin限定(グローバル既定には
                // 判定に使えるプロジェクトメンバーシップが無いため)。
                new Endpoint("GET", "/api/tag-design-settings"),
                new Endpoint("PUT", "/api/tag-design-settings/plantuml"),
                new Endpoint("POST", "/api/tag-design-settings/plantuml/generate"),

                // -- 内部ブリッジ(TagDesignInternalController) --
                new Endpoint("GET", "/api/internal/project/tag-design/plantuml"),

                // -- 内部ブリッジ(ProjectInternalController) --
                new Endpoint("GET", "/api/internal/project/projects/1"),
                new Endpoint("POST", "/api/internal/project/projects/1/sns/pv/sync"),
                new Endpoint("POST", "/api/internal/project/letsblog-sync"),
                new Endpoint("GET", "/api/internal/project/projects/1/github-token"),
                new Endpoint("PUT", "/api/internal/project/projects/1/github-token"),
                new Endpoint("DELETE", "/api/internal/project/projects/1/github-token"),
                new Endpoint("GET", "/api/internal/project/sites/1/project-id"),
                new Endpoint("GET", "/api/internal/project/sites/1"),
                new Endpoint("GET", "/api/internal/project/sites/by-key/site-key"),
                new Endpoint("GET", "/api/internal/project/sites"),

                // -- 内部ブリッジ(SiteCredentialsInternalController) --
                new Endpoint("GET", "/api/internal/project/sites/site-key/credentials"),

                // -- ProjectGithubTokenController(issue #583でlegacy-apiから移設) --
                new Endpoint("GET", "/api/projects/1/api-keys/github-token"),
                new Endpoint("PUT", "/api/projects/1/api-keys/github-token"),
                new Endpoint("DELETE", "/api/projects/1/api-keys/github-token"),

                // -- 内部ブリッジ(ProjectInternalController、issue #583で legacy-api から引き取った分) --
                new Endpoint("GET", "/api/internal/project/projects/1/eligibility"),
                new Endpoint("GET", "/api/internal/project/users/1/site-ids"),
                new Endpoint("GET", "/api/internal/project/projects/1/github-access"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("allProtectedEndpoints")
    @DisplayName("Authorizationヘッダーなしなら例外なく401")
    void everyProtectedEndpoint_returns401WithoutAuthorization(Endpoint endpoint) throws Exception {
        mockMvc.perform(request(HttpMethod.valueOf(endpoint.method()), endpoint.path()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/projects: 不正なJWTも401")
    void 不正なjwtは401() throws Exception {
        when(jwtDecoder.decode("invalid-jwt")).thenThrow(new BadJwtException("invalid token"));

        mockMvc.perform(request(HttpMethod.GET, "/api/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("有効なJWTがあれば認証ゲートは通過する(401にならない)")
    void 有効なjwtなら401にならない() throws Exception {
        mockMvc.perform(request(HttpMethod.GET, "/api/projects")
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
                "project", allProtectedEndpoints().toList());
    }
}
