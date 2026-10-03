package com.letsblog.publishing.integration;

import com.letsblog.common.testfixtures.AuthorizationMatrixContract;
import com.letsblog.common.testfixtures.AuthorizationMatrixContract.Endpoint;
import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.publishing.client.IdentityClient;
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
 * issue #772: publishing-serviceの認証ゲート(「有効なKeycloak JWTが無ければ401」)の網羅テスト。
 * ADR-0008が必須と定める後退検知手段であり、platform-serviceの同名クラス(#705)をテンプレートに
 * している。{@code SecurityConfig}が{@code permitAll}へ戻れば、このクラスの401アサーションが落ちる。
 *
 * <p>#707/#708/#709/#712でlegacy-apiから移設した時点では{@code /api/internal/**}のみ
 * {@code authenticated()}で、残りは{@code permitAll()}だった。legacy-api時代には
 * {@code anyRequest().authenticated()}で401だった{@code /api/posts/publish}等が未認証で
 * 到達できる後退が残っていた(issue #705と同型)。本クラスはその再発を防ぐ。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("publishing-service: 認証ゲートの認可マトリクス統合テスト(issue #772)")
class AuthorizationMatrixIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private IdentityClient identityClient;

    /**
     * publishing-serviceがgateway経由で外部へ公開している全エンドポイント
     * ({@code posts-publish-legacy}・{@code posts-delete-legacy}・{@code publishing}・
     * {@code project-bulk-management-legacy}・{@code project-asset-images-legacy}・
     * {@code project-preview-legacy}・{@code project-article-review}ルート)と、サービス間内部ブリッジ
     * ({@code /api/internal/publishing/**}・{@code /api/internal/ai/**}・
     * {@code /api/internal/project/cms/**}。#707時点から認証必須で、呼び出し元はBearerトークンを
     * 転送する。docs/SYNC_SERVICE_CALLS.md参照)。
     *
     * <p>Authorizationヘッダーの有無だけでSecurityConfigが401を返すため、リクエストボディ/
     * クエリパラメータの妥当性は問わない。
     */
    static Stream<Endpoint> allProtectedEndpoints() {
        return Stream.of(
                // -- PostController --
                new Endpoint("POST", "/api/posts/publish"),
                new Endpoint("DELETE", "/api/posts/site-key/123"),

                // -- TaxonomyController --
                new Endpoint("POST", "/api/taxonomy/resolve"),

                // -- ArticlePreviewController --
                new Endpoint("GET", "/api/projects/1/preview/theme-css"),
                new Endpoint("POST", "/api/projects/1/preview/skeleton"),
                new Endpoint("DELETE", "/api/projects/1/preview/preview-post"),

                // -- ArticleReviewController --
                new Endpoint("GET", "/api/projects/1/article-review/pull-requests"),
                new Endpoint("GET", "/api/projects/1/article-review/pull-requests/1/article"),
                new Endpoint("POST", "/api/projects/1/article-review/submissions"),
                new Endpoint("POST", "/api/projects/1/article-review/pull-requests/1/review"),
                new Endpoint("POST", "/api/projects/1/article-review/pull-requests/1/reject"),
                new Endpoint("POST", "/api/projects/1/article-review/pull-requests/1/approve"),
                new Endpoint("GET", "/api/projects/1/article-review/my-reviews"),

                // -- BulkManagementController --
                new Endpoint("POST", "/api/projects/1/bulk-management/apply"),
                new Endpoint("POST", "/api/projects/1/bulk-management/apply-all"),
                new Endpoint("POST", "/api/projects/1/bulk-management/upload"),
                new Endpoint("POST", "/api/projects/1/asset-images/1/upload"),
                new Endpoint("GET", "/api/projects/1/bulk-management/categories/comparison"),
                new Endpoint("GET", "/api/projects/1/bulk-management/tags/comparison"),
                new Endpoint("POST", "/api/projects/1/bulk-management/categories/sync"),
                new Endpoint("POST", "/api/projects/1/bulk-management/categories/delete-all"),
                new Endpoint("POST", "/api/projects/1/bulk-management/categories/edit-sync"),
                new Endpoint("POST", "/api/projects/1/bulk-management/categories/sync-all"),
                new Endpoint("POST", "/api/projects/1/bulk-management/tags/sync"),
                new Endpoint("POST", "/api/projects/1/bulk-management/tags/delete-all"),
                new Endpoint("POST", "/api/projects/1/bulk-management/tags/edit-sync"),
                new Endpoint("POST", "/api/projects/1/bulk-management/tags/sync-all"),
                new Endpoint("GET", "/api/projects/1/bulk-management/plugins/comparison"),
                new Endpoint("GET", "/api/projects/1/bulk-management/themes/comparison"),
                new Endpoint("POST", "/api/projects/1/bulk-management/plugins/reconcile"),
                new Endpoint("POST", "/api/projects/1/bulk-management/themes/reconcile"),
                new Endpoint("POST", "/api/projects/1/bulk-management/plugins/delete-all"),
                new Endpoint("POST", "/api/projects/1/bulk-management/themes/delete-all"),
                new Endpoint("GET", "/api/projects/1/bulk-management/posts/comparison"),
                new Endpoint("POST", "/api/projects/1/bulk-management/posts/delete-all"),
                new Endpoint("POST", "/api/projects/1/bulk-management/posts/status-update"),

                // -- 内部ブリッジ(AuthorProvisioningInternalController) --
                new Endpoint("POST", "/api/internal/publishing/sites/site-key/authors"),

                // -- 内部ブリッジ(CmsMediaBridgeController) --
                new Endpoint("POST", "/api/internal/publishing/sites/site-key/media"),
                new Endpoint("GET", "/api/internal/publishing/projects/1/media-scan"),
                new Endpoint("DELETE", "/api/internal/publishing/projects/1/media/1"),

                // -- 内部ブリッジ(AiExistingTaxonomyBridgeController) --
                new Endpoint("GET", "/api/internal/ai/projects/1/existing-categories"),
                new Endpoint("GET", "/api/internal/ai/projects/1/existing-categories-with-parents"),
                new Endpoint("GET", "/api/internal/ai/projects/1/existing-tags"),

                // -- 内部ブリッジ(CmsProvisioningBridgeController) --
                new Endpoint("POST", "/api/internal/project/cms/test-connection"),
                new Endpoint("POST", "/api/internal/project/cms/install-wp-cli"),
                new Endpoint("POST", "/api/internal/project/cms/has-author-capability"),
                new Endpoint("POST", "/api/internal/project/cms/list-active-plugins"),
                new Endpoint("POST", "/api/internal/project/cms/provision"),
                new Endpoint("POST", "/api/internal/project/cms/export-database"),
                new Endpoint("POST", "/api/internal/project/cms/export-media"),
                new Endpoint("POST", "/api/internal/project/cms/export-themes"),

                // 一覧に載っていなかった内部ブリッジ(#583の検証で発覚。追加時に載せ忘れていたもので、
                // #583の変更とは無関係)。
                new Endpoint("GET", "/api/internal/publishing/sites/site-key/project-id"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("allProtectedEndpoints")
    @DisplayName("Authorizationヘッダーなしなら例外なく401")
    void everyProtectedEndpoint_returns401WithoutAuthorization(Endpoint endpoint) throws Exception {
        mockMvc.perform(request(HttpMethod.valueOf(endpoint.method()), endpoint.path()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/taxonomy/resolve: 不正なJWTも401")
    void 不正なjwtは401() throws Exception {
        when(jwtDecoder.decode("invalid-jwt")).thenThrow(new BadJwtException("invalid token"));

        mockMvc.perform(request(HttpMethod.POST, "/api/taxonomy/resolve")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("有効なJWTがあれば認証ゲートは通過する(401にならない)")
    void 有効なjwtなら401にならない() throws Exception {
        mockMvc.perform(request(HttpMethod.POST, "/api/taxonomy/resolve")
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
                "publishing", allProtectedEndpoints().toList());
    }
}
