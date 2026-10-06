package com.letsblog.media.integration;

import com.letsblog.common.client.IdentityClient;
import com.letsblog.common.testfixtures.AuthorizationMatrixContract;
import com.letsblog.common.testfixtures.AuthorizationMatrixContract.Endpoint;
import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.media.service.MediaGarbageCollectionService;
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
 * issue #772: media-serviceの認証ゲート(「有効なKeycloak JWTが無ければ401」)の網羅テスト。
 * ADR-0008が必須と定める後退検知手段であり、platform-serviceの同名クラス(#705)をテンプレートに
 * している。{@code SecurityConfig}が{@code permitAll}へ戻れば、このクラスの401アサーションが落ちる。
 *
 * <p>#573でlegacy-apiから移設した各コントローラは、legacy-api時代には
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
@DisplayName("media-service: 認証ゲートの認可マトリクス統合テスト(issue #772)")
class AuthorizationMatrixIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private IdentityClient identityClient;

    @MockitoBean
    private MediaGarbageCollectionService mediaGarbageCollectionService;

    /**
     * media-serviceがgateway経由で外部へ公開している全エンドポイント(gatewayの{@code media}ルート:
     * {@code /api/generated-images/**}・{@code /api/diagrams/**}・{@code /api/render/**}・
     * {@code /api/media/**}、{@code project-media-garbage-collection}ルート、および
     * fallback経由で到達する{@code /api/comfyui/**})。本サービスは{@code /api/internal/**}配下の
     * 内部ブリッジを持たないが、{@code /api/render/**}等はcontent-service/publishing-service/
     * legacy-apiからのサービス間呼び出しでも叩かれる(いずれもBearerトークンを転送するため
     * 認証必須で問題ない。docs/SYNC_SERVICE_CALLS.md参照)。
     *
     * <p>Authorizationヘッダーの有無だけでSecurityConfigが401を返すため、リクエストボディ/
     * クエリパラメータの妥当性は問わない。
     */
    static Stream<Endpoint> allProtectedEndpoints() {
        return Stream.of(
                // -- ComfyUiCheckpointController --
                new Endpoint("POST", "/api/comfyui/checkpoints/install"),
                new Endpoint("POST", "/api/comfyui/checkpoints/delete"),

                // -- MediaController --
                new Endpoint("POST", "/api/media/upload"),

                // -- DiagramController --
                new Endpoint("POST", "/api/diagrams"),
                new Endpoint("GET", "/api/diagrams"),
                new Endpoint("GET", "/api/diagrams/1"),
                new Endpoint("GET", "/api/diagrams/1/svg"),
                new Endpoint("PUT", "/api/diagrams/1"),
                new Endpoint("DELETE", "/api/diagrams/1"),

                // -- RenderController --
                new Endpoint("POST", "/api/render/plantuml"),
                new Endpoint("POST", "/api/render/recharts"),
                new Endpoint("POST", "/api/render/penpot/design-file"),

                // -- GeneratedImageController --
                new Endpoint("GET", "/api/generated-images"),
                new Endpoint("GET", "/api/generated-images/1"),
                new Endpoint("POST", "/api/generated-images"),
                new Endpoint("PUT", "/api/generated-images/1/tags"),
                new Endpoint("GET", "/api/generated-images/1/file"),
                new Endpoint("DELETE", "/api/generated-images/1"),
                new Endpoint("POST", "/api/generated-images/bulk-delete"),
                // issue #1599
                new Endpoint("POST", "/api/generated-images/upload"),
                // issue #1655
                new Endpoint("POST", "/api/generated-images/1/edit"),
                // issue #1493
                new Endpoint("PUT", "/api/generated-images/1/folder"),
                new Endpoint("GET", "/api/generated-images/folders"),
                new Endpoint("POST", "/api/generated-images/folders"),
                new Endpoint("PUT", "/api/generated-images/folders/1/parent"),
                new Endpoint("PUT", "/api/generated-images/folders/1/name"),
                new Endpoint("GET", "/api/generated-images/folders/1/delete-impact"),
                new Endpoint("DELETE", "/api/generated-images/folders/1"),

                // -- ProjectMediaGarbageCollectionController --
                new Endpoint("GET", "/api/projects/1/media-garbage-collection/scan"),
                new Endpoint("POST", "/api/projects/1/media-garbage-collection/delete"),

                // -- ImageGenerationController(issue #583でlegacy-apiから移設) --
                new Endpoint("POST", "/api/ai/image"),
                new Endpoint("POST", "/api/ai/image/jobs"),
                new Endpoint("GET", "/api/ai/image-options"),

                // -- ProjectImageModelController(issue #583でlegacy-apiから移設) --
                new Endpoint("GET", "/api/projects/1/ai-models/image/provider"),
                new Endpoint("PUT", "/api/projects/1/ai-models/image/provider/selection"),
                new Endpoint("GET", "/api/projects/1/ai-models/comfyui/checkpoints"),
                new Endpoint("PUT", "/api/projects/1/ai-models/comfyui/checkpoints/selection"),
                new Endpoint("POST", "/api/projects/1/ai-models/comfyui/checkpoints/install"),
                new Endpoint("DELETE", "/api/projects/1/ai-models/comfyui/checkpoints/model.safetensors"),

                // -- ProjectImageSettingsController(issue #583でlegacy-apiから移設) --
                new Endpoint("GET", "/api/projects/1/image-settings"),
                new Endpoint("PUT", "/api/projects/1/image-generation-prompt-defaults"),
                new Endpoint("PUT", "/api/projects/1/image-generation-size-defaults"),
                new Endpoint("PUT", "/api/projects/1/article-image-resize-default"),
                new Endpoint("PUT", "/api/projects/1/image-content-filter-settings"),

                // -- 内部ブリッジ(InternalProjectImageSettingsController、issue #583) --
                new Endpoint("GET", "/api/internal/media/projects/1/article-image-long-edge-px"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("allProtectedEndpoints")
    @DisplayName("Authorizationヘッダーなしなら例外なく401")
    void everyProtectedEndpoint_returns401WithoutAuthorization(Endpoint endpoint) throws Exception {
        mockMvc.perform(request(HttpMethod.valueOf(endpoint.method()), endpoint.path()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/diagrams: 不正なJWTも401")
    void 不正なjwtは401() throws Exception {
        when(jwtDecoder.decode("invalid-jwt")).thenThrow(new BadJwtException("invalid token"));

        mockMvc.perform(request(HttpMethod.GET, "/api/diagrams")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("有効なJWTがあれば認証ゲートは通過する(401にならない)")
    void 有効なjwtなら401にならない() throws Exception {
        mockMvc.perform(request(HttpMethod.GET, "/api/diagrams")
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
                "media", allProtectedEndpoints().toList());
    }
}
