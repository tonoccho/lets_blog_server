package com.letsblog.content.integration;

import com.letsblog.common.client.IdentityClient;
import com.letsblog.common.testfixtures.AuthorizationMatrixContract;
import com.letsblog.common.testfixtures.AuthorizationMatrixContract.Endpoint;
import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.content.client.IdentityBridgeClient;
import com.letsblog.content.client.ProjectBridgeClient;
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
 * issue #772: content-serviceの認証ゲート(「有効なKeycloak JWTが無ければ401」)の網羅テスト。
 * ADR-0008が必須と定める後退検知手段であり、platform-serviceの同名クラス(#705)をテンプレートに
 * している。{@code SecurityConfig}が{@code permitAll}へ戻れば、このクラスの401アサーションが落ちる。
 *
 * <p>#576でlegacy-apiから移設した各コントローラは、legacy-api時代には
 * {@code anyRequest().authenticated()}によって「Authorizationヘッダーが無ければ401」だった。
 * 移設先の{@code SecurityConfig}が他サービスのテンプレート通り全経路{@code permitAll()}だったため、
 * その認証ゲートが失われていた(issue #705と同型の後退)。本クラスはその再発を防ぐ。
 *
 * <p>ロール単位の認可(プロジェクトメンバー/adminの403)は{@link AdminAuthorizationIntegrationTest}が
 * 担当し、ここでは重複させない。
 *
 * <p>identity-service/identity-service/project-serviceは外部境界のため{@link IdentityClient}/{@link IdentityBridgeClient}/{@link ProjectBridgeClient}を
 * {@code @MockitoBean}で置き換える(ADR-0006のモック方針)。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("content-service: 認証ゲートの認可マトリクス統合テスト(issue #772)")
class AuthorizationMatrixIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private IdentityClient identityClient;

    @MockitoBean
    private IdentityBridgeClient identityBridgeClient;

    @MockitoBean
    private ProjectBridgeClient projectBridgeClient;

    /**
     * content-serviceがgateway経由で外部へ公開している全エンドポイント(gatewayの{@code content}ルート:
     * {@code /api/posts/**}・{@code /api/custom-tags/**}・{@code /api/custom-tag-templates/**}・
     * {@code /api/content-cache/**}・{@code /api/metadata/**}、および{@code project-custom-tags}・
     * {@code project-preview-render}ルート)と、サービス間内部ブリッジ
     * ({@code /api/internal/content/**}。呼び出し元はいずれもBearerトークンを転送するため
     * 認証必須で問題ない。docs/SYNC_SERVICE_CALLS.md参照)。
     *
     * <p>Authorizationヘッダーの有無だけでSecurityConfigが401を返すため、リクエストボディ/
     * クエリパラメータの妥当性は問わない。
     */
    static Stream<Endpoint> allProtectedEndpoints() {
        return Stream.of(
                // -- CustomTagController --
                new Endpoint("POST", "/api/custom-tags/generate"),
                new Endpoint("POST", "/api/custom-tags/validate"),
                new Endpoint("POST", "/api/custom-tags"),
                new Endpoint("GET", "/api/custom-tags"),
                new Endpoint("GET", "/api/custom-tags/css-bundle"),
                new Endpoint("PUT", "/api/custom-tags/1"),
                new Endpoint("DELETE", "/api/custom-tags/1"),

                // -- ProjectCustomTagController --
                new Endpoint("GET", "/api/projects/1/custom-tags"),
                new Endpoint("GET", "/api/projects/1/custom-tags/css-bundle"),
                new Endpoint("POST", "/api/projects/1/custom-tags/preview"),

                // -- CustomTagTemplateController --
                new Endpoint("POST", "/api/custom-tag-templates"),
                new Endpoint("GET", "/api/custom-tag-templates"),
                new Endpoint("GET", "/api/custom-tag-templates/1"),
                new Endpoint("GET", "/api/custom-tag-templates/my-templates"),
                new Endpoint("PUT", "/api/custom-tag-templates/1"),
                new Endpoint("POST", "/api/custom-tag-templates/1/publish"),
                new Endpoint("POST", "/api/custom-tag-templates/1/unpublish"),
                new Endpoint("POST", "/api/custom-tag-templates/1/clone"),
                new Endpoint("DELETE", "/api/custom-tag-templates/1"),

                // -- PostController --
                new Endpoint("GET", "/api/posts"),
                new Endpoint("GET", "/api/posts/site-key/by-slug/some-slug"),

                // -- MetadataController --
                new Endpoint("GET", "/api/metadata/post-statuses"),
                new Endpoint("GET", "/api/metadata/roles"),

                // -- ContentCacheController --
                new Endpoint("GET", "/api/content-cache"),

                // -- ArticlePreviewController --
                new Endpoint("POST", "/api/projects/1/preview/render"),

                // -- 内部ブリッジ(InternalPostBridgeController) --
                new Endpoint("GET", "/api/internal/content/posts"),
                new Endpoint("PUT", "/api/internal/content/posts"),
                new Endpoint("POST", "/api/internal/content/posts/mark-trashed"),
                new Endpoint("DELETE", "/api/internal/content/posts/by-site/1"),

                // -- 内部ブリッジ(InternalPublishPipelineController) --
                new Endpoint("POST", "/api/internal/content/render/pre-image"),
                new Endpoint("POST", "/api/internal/content/render/finalize-html"),

                // -- 内部ブリッジ(InternalPreviewSkeletonController) --
                new Endpoint("POST", "/api/internal/content/preview-skeleton/fetch-and-splice"),
                new Endpoint("POST", "/api/internal/content/preview-skeleton/fetch-real-post"),

                // -- 内部ブリッジ(InternalProjectContentSettingsController) --
                new Endpoint("GET", "/api/internal/content/projects/1/content-settings"),
                new Endpoint("PUT", "/api/internal/content/projects/1/content-settings"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("allProtectedEndpoints")
    @DisplayName("Authorizationヘッダーなしなら例外なく401")
    void everyProtectedEndpoint_returns401WithoutAuthorization(Endpoint endpoint) throws Exception {
        mockMvc.perform(request(HttpMethod.valueOf(endpoint.method()), endpoint.path()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/custom-tags: 不正なJWTも401")
    void customTags_不正なjwtは401() throws Exception {
        when(jwtDecoder.decode("invalid-jwt")).thenThrow(new BadJwtException("invalid token"));

        mockMvc.perform(request(HttpMethod.GET, "/api/custom-tags")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("有効なJWTがあれば認証ゲートは通過する(200)")
    void 有効なjwtなら通過する() throws Exception {
        mockMvc.perform(request(HttpMethod.GET, "/api/metadata/post-statuses")
                        .with(JwtTestFixtures.jwtRequestPostProcessor("sub-772", "user")))
                .andExpect(status().isOk());
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
                "content", allProtectedEndpoints().toList());
    }
}
