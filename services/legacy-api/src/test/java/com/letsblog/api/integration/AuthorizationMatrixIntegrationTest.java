package com.letsblog.api.integration;

import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.ProjectUser;
import com.letsblog.api.domain.User;
import com.letsblog.api.repository.ProjectRepository;
import com.letsblog.api.repository.ProjectUserRepository;
import com.letsblog.api.repository.UserRepository;
import com.letsblog.api.service.ApiKeyService;
import com.letsblog.common.testfixtures.JwtTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #568: 認可マトリクスの整備に伴う統合テスト。
 *
 * <p>現行(#591カットオーバー前)の認可モデルは2層構造。
 * <ol>
 *   <li>{@link com.letsblog.api.config.ApiKeyAuthFilter} — X-API-Keyが無い/不正なら、コントローラ
 *       に到達する前に401を返す({@code /api/health}と一部の認証系公開パスを除く全{@code /api/**}が対象)。</li>
 *   <li>コントローラメソッドが呼ぶ{@code AdminAuthorizationService.requireAdmin()} /
 *       {@code requireProjectMemberOrAdmin(projectId)} — 満たさなければ403({@link
 *       com.letsblog.api.service.ForbiddenException}を{@code GlobalExceptionHandler}が403へ変換)。</li>
 * </ol>
 *
 * <p>本クラスは3点を検証する。
 * <ul>
 *   <li>(a) {@code docs/AUTHORIZATION_MATRIX.md}に列挙した全エンドポイント(health・認証系公開パスを除く)
 *       について、X-API-Keyなしでは例外なく401になること。</li>
 *   <li>(b) requireAdmin()で保護された代表的なエンドポイントについて、非adminは403・adminは403にならない
 *       ことを実HTTPで検証する(requireAdmin()自体の網羅的な単体テストは{@code
 *       AdminAuthorizationServiceTest}に既にあるため、ここでは重複させない)。</li>
 *   <li>(c) requireProjectMemberOrAdmin()について、プロジェクトAのメンバーがプロジェクトB(非所属)を
 *       操作しようとすると403になり、adminは所属に関わらず403にならないことを実DBで検証する。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("認可マトリクス統合テスト(issue #568)")
class AuthorizationMatrixIntegrationTest {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String TEST_API_KEY = "lb_test-key";
    private static final String ACTOR_ID_HEADER = "X-Actor-Id";
    private static final String ACTOR_ROLE_HEADER = "X-Actor-Role";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectUserRepository projectUserRepository;

    @BeforeEach
    void setUpApiKeyAuth() {
        when(apiKeyService.resolveUserId(TEST_API_KEY)).thenReturn(Optional.of(1L));
        // jwtDecoderは(a)の401網羅テスト等Authorizationヘッダーを送らないテストでは一切呼ばれない
        // ため、ここではスタブせず各JWT関連テストで個別に振る舞いを定義する。
    }

    // =====================================================================================
    // (a) 全エンドポイント(health・認証系公開パスを除く)の401網羅
    //
    // services/legacy-api/src/main/java/com/letsblog/api/controller/ の33ファイル・191エンドポイント
    // (@GetMapping/@PostMapping/@PutMapping/@DeleteMapping/@PatchMappingの合計、grepで確認済み)から、
    // ApiKeyAuthFilter.PUBLIC_AUTH_PATHS(7パス)と/api/healthを除いた183件を列挙する。
    // パスパラメータには存在確認不要な適当な値(1、"slug"等)を埋める。X-API-Keyの有無だけで
    // ApiKeyAuthFilterが401を返すため、リクエストボディ/クエリパラメータの妥当性は問わない。
    // =====================================================================================

    record Endpoint(String method, String path) {
        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    static Stream<Endpoint> allProtectedEndpoints() {
        return Stream.of(
                // -- AiController (8) --
                new Endpoint("POST", "/api/ai/draft"),
                new Endpoint("POST", "/api/ai/ask"),
                new Endpoint("POST", "/api/ai/tags"),
                new Endpoint("POST", "/api/ai/proofread"),
                new Endpoint("POST", "/api/ai/image"),
                new Endpoint("GET", "/api/ai/image-options"),
                new Endpoint("POST", "/api/ai/section"),
                new Endpoint("POST", "/api/projects/1/ai/generate-image-prompt"),

                // -- AppSettingController (2) --
                new Endpoint("GET", "/api/system-settings/app-settings"),
                new Endpoint("PUT", "/api/system-settings/app-settings"),

                // -- ArticlePlanController (15) --
                new Endpoint("POST", "/api/projects/1/article-plan/chat"),
                new Endpoint("GET", "/api/projects/1/article-plan/sessions"),
                new Endpoint("GET", "/api/projects/1/article-plan/sessions/5"),
                new Endpoint("GET", "/api/projects/1/article-plan/sessions/by-issue/42"),
                new Endpoint("GET", "/api/projects/1/article-plan/issues/42/description"),
                new Endpoint("GET", "/api/projects/1/article-plan/issues"),
                new Endpoint("POST", "/api/projects/1/article-plan/suggest-titles"),
                new Endpoint("POST", "/api/projects/1/article-plan/accept"),
                new Endpoint("POST", "/api/projects/1/article-plan/suggest-structure"),
                new Endpoint("POST", "/api/projects/1/article-plan/issues/42/accept-structure"),
                new Endpoint("POST", "/api/projects/1/article-plan/suggest-metadata"),
                new Endpoint("GET", "/api/projects/1/article-plan/categories"),
                new Endpoint("GET", "/api/projects/1/article-plan/categories/hierarchy"),
                new Endpoint("GET", "/api/projects/1/article-plan/tags"),
                new Endpoint("POST", "/api/projects/1/article-plan/issues/42/assign"),

                // -- ArticlePreviewController (4) --
                new Endpoint("POST", "/api/projects/1/preview/render"),
                new Endpoint("GET", "/api/projects/1/preview/theme-css"),
                new Endpoint("POST", "/api/projects/1/preview/skeleton"),
                new Endpoint("DELETE", "/api/projects/1/preview/preview-post"),

                // -- AuditLogController (1) --
                new Endpoint("GET", "/api/audit-logs"),

                // -- AuthController (11件中、公開パス7件を除く4件) --
                new Endpoint("GET", "/api/auth/totp/status"),
                new Endpoint("POST", "/api/auth/totp/setup"),
                new Endpoint("POST", "/api/auth/totp/verify-setup"),
                new Endpoint("POST", "/api/auth/totp/disable"),

                // -- BackupController (2) --
                new Endpoint("GET", "/api/backup/download"),
                new Endpoint("POST", "/api/backup/restore"),

                // -- ContentCacheController (1) --
                new Endpoint("GET", "/api/content-cache"),

                // -- CustomTagController (7) --
                new Endpoint("POST", "/api/custom-tags/generate"),
                new Endpoint("POST", "/api/custom-tags/validate"),
                new Endpoint("POST", "/api/custom-tags"),
                new Endpoint("GET", "/api/custom-tags"),
                new Endpoint("GET", "/api/custom-tags/css-bundle"),
                new Endpoint("PUT", "/api/custom-tags/1"),
                new Endpoint("DELETE", "/api/custom-tags/1"),

                // -- CustomTagTemplateController (9) --
                new Endpoint("POST", "/api/custom-tag-templates"),
                new Endpoint("GET", "/api/custom-tag-templates/1"),
                new Endpoint("GET", "/api/custom-tag-templates"),
                new Endpoint("GET", "/api/custom-tag-templates/my-templates"),
                new Endpoint("PUT", "/api/custom-tag-templates/1"),
                new Endpoint("POST", "/api/custom-tag-templates/1/publish"),
                new Endpoint("POST", "/api/custom-tag-templates/1/unpublish"),
                new Endpoint("POST", "/api/custom-tag-templates/1/clone"),
                new Endpoint("DELETE", "/api/custom-tag-templates/1"),

                // -- DashboardController (5) --
                new Endpoint("GET", "/api/dashboard/service-status"),
                new Endpoint("GET", "/api/dashboard/service-status/stream"),
                new Endpoint("GET", "/api/dashboard/service-status/detail"),
                new Endpoint("GET", "/api/dashboard/container-status"),
                new Endpoint("GET", "/api/dashboard/container-status/stream"),

                // -- DiagramController (6) --
                new Endpoint("POST", "/api/diagrams"),
                new Endpoint("GET", "/api/diagrams"),
                new Endpoint("GET", "/api/diagrams/1"),
                new Endpoint("GET", "/api/diagrams/1/svg"),
                new Endpoint("PUT", "/api/diagrams/1"),
                new Endpoint("DELETE", "/api/diagrams/1"),

                // -- FrontendErrorLogController (2) --
                new Endpoint("POST", "/api/logs/errors"),
                new Endpoint("GET", "/api/logs/errors"),

                // -- GeneratedImageController (5) --
                new Endpoint("GET", "/api/generated-images"),
                new Endpoint("GET", "/api/generated-images/1"),
                new Endpoint("PUT", "/api/generated-images/1/tags"),
                new Endpoint("GET", "/api/generated-images/1/file"),
                new Endpoint("DELETE", "/api/generated-images/1"),

                // -- GenerationJobController (2) --
                new Endpoint("GET", "/api/generation-jobs"),
                new Endpoint("GET", "/api/generation-jobs/1"),

                // (HealthController /api/health は公開パスのため対象外)

                // -- MediaController (1) --
                new Endpoint("POST", "/api/media/upload"),

                // -- MetadataController (2) --
                new Endpoint("GET", "/api/metadata/post-statuses"),
                new Endpoint("GET", "/api/metadata/roles"),

                // -- OperationLogController (4) --
                new Endpoint("POST", "/api/operation-logs"),
                new Endpoint("GET", "/api/operation-logs"),
                new Endpoint("GET", "/api/operation-logs/op-1"),
                new Endpoint("GET", "/api/operation-logs/unified"),

                // -- PostController (4) --
                new Endpoint("GET", "/api/posts"),
                new Endpoint("POST", "/api/posts/publish"),
                new Endpoint("GET", "/api/posts/mysite/by-slug/my-slug"),
                new Endpoint("DELETE", "/api/posts/mysite/123"),

                // -- ProjectAiModelController (10) --
                new Endpoint("GET", "/api/projects/1/ai-models/llm/models"),
                new Endpoint("PUT", "/api/projects/1/ai-models/llm/models/selection"),
                new Endpoint("GET", "/api/projects/1/ai-models/llm/provider"),
                new Endpoint("PUT", "/api/projects/1/ai-models/llm/provider/selection"),
                new Endpoint("GET", "/api/projects/1/ai-models/image/provider"),
                new Endpoint("PUT", "/api/projects/1/ai-models/image/provider/selection"),
                new Endpoint("GET", "/api/projects/1/ai-models/comfyui/checkpoints"),
                new Endpoint("PUT", "/api/projects/1/ai-models/comfyui/checkpoints/selection"),
                new Endpoint("POST", "/api/projects/1/ai-models/comfyui/checkpoints/install"),
                new Endpoint("DELETE", "/api/projects/1/ai-models/comfyui/checkpoints/ckpt.safetensors"),

                // -- ProjectApiKeyController (14) --
                new Endpoint("GET", "/api/projects/1/api-keys/github-token"),
                new Endpoint("PUT", "/api/projects/1/api-keys/github-token"),
                new Endpoint("DELETE", "/api/projects/1/api-keys/github-token"),
                new Endpoint("GET", "/api/projects/1/api-keys/brave-search-api-key"),
                new Endpoint("PUT", "/api/projects/1/api-keys/brave-search-api-key"),
                new Endpoint("DELETE", "/api/projects/1/api-keys/brave-search-api-key"),
                new Endpoint("GET", "/api/projects/1/api-keys/google-analytics"),
                new Endpoint("PUT", "/api/projects/1/api-keys/google-analytics"),
                new Endpoint("DELETE", "/api/projects/1/api-keys/google-analytics"),
                new Endpoint("GET", "/api/projects/1/api-keys/adsense"),
                new Endpoint("PUT", "/api/projects/1/api-keys/adsense"),
                new Endpoint("PUT", "/api/projects/1/api-keys/adsense/client-secret"),
                new Endpoint("DELETE", "/api/projects/1/api-keys/adsense"),
                new Endpoint("POST", "/api/projects/1/api-keys/adsense/oauth-callback"),

                // -- ProjectController (42) --
                new Endpoint("POST", "/api/projects"),
                new Endpoint("GET", "/api/projects"),
                new Endpoint("GET", "/api/projects/1"),
                new Endpoint("PUT", "/api/projects/1"),
                new Endpoint("DELETE", "/api/projects/1"),
                new Endpoint("POST", "/api/projects/1/environments"),
                new Endpoint("DELETE", "/api/projects/1/environments/local"),
                new Endpoint("PUT", "/api/projects/1/master-environment"),
                new Endpoint("PUT", "/api/projects/1/github-repository"),
                new Endpoint("PUT", "/api/projects/1/css-selector-prefix"),
                new Endpoint("PUT", "/api/projects/1/image-generation-prompt-defaults"),
                new Endpoint("PUT", "/api/projects/1/image-generation-size-defaults"),
                new Endpoint("PUT", "/api/projects/1/article-image-resize-default"),
                new Endpoint("PUT", "/api/projects/1/image-content-filter-settings"),
                new Endpoint("POST", "/api/projects/1/environments/sync"),
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
                new Endpoint("GET", "/api/projects/1/users"),
                new Endpoint("POST", "/api/projects/1/users"),
                new Endpoint("PUT", "/api/projects/1/users/2"),
                new Endpoint("DELETE", "/api/projects/1/users/2"),

                // -- ProjectCustomTagController (3) --
                new Endpoint("GET", "/api/projects/1/custom-tags"),
                new Endpoint("GET", "/api/projects/1/custom-tags/css-bundle"),
                new Endpoint("POST", "/api/projects/1/custom-tags/preview"),

                // -- ProjectDashboardController (2) --
                new Endpoint("GET", "/api/projects/1/dashboard/google-analytics"),
                new Endpoint("GET", "/api/projects/1/dashboard/adsense"),

                // -- ProjectMediaGarbageCollectionController (2) --
                new Endpoint("GET", "/api/projects/1/media-garbage-collection/scan"),
                new Endpoint("POST", "/api/projects/1/media-garbage-collection/delete"),

                // -- ProjectUserController (1) --
                new Endpoint("GET", "/api/project-users"),

                // -- RenderController (1) --
                new Endpoint("POST", "/api/render/plantuml"),

                // -- SiteController (11) --
                new Endpoint("POST", "/api/sites"),
                new Endpoint("POST", "/api/sites/managed-wordpress"),
                new Endpoint("POST", "/api/sites/managed-wordpress/adopt"),
                new Endpoint("GET", "/api/sites"),
                new Endpoint("GET", "/api/sites/1"),
                new Endpoint("POST", "/api/sites/ssh-keypair"),
                new Endpoint("PUT", "/api/sites/1"),
                new Endpoint("POST", "/api/sites/1/test-connection"),
                new Endpoint("DELETE", "/api/sites/1"),
                new Endpoint("POST", "/api/sites/1/install-wp-cli"),
                new Endpoint("POST", "/api/sites/1/reprovision"),

                // -- SiteStaticContentController (2) --
                new Endpoint("GET", "/api/sites/1/static-content"),
                new Endpoint("POST", "/api/sites/1/static-content/generate"),

                // -- SshKeyPairController (3) --
                new Endpoint("GET", "/api/ssh-key-pairs"),
                new Endpoint("POST", "/api/ssh-key-pairs"),
                new Endpoint("DELETE", "/api/ssh-key-pairs/1"),

                // -- SystemSettingController (3) --
                new Endpoint("GET", "/api/system-settings/brave-search-api-key"),
                new Endpoint("PUT", "/api/system-settings/brave-search-api-key"),
                new Endpoint("DELETE", "/api/system-settings/brave-search-api-key"),

                // -- TagDesignSettingController (3) --
                new Endpoint("GET", "/api/projects/1/tag-design-settings"),
                new Endpoint("PUT", "/api/projects/1/tag-design-settings/TOC"),
                new Endpoint("POST", "/api/projects/1/tag-design-settings/TOC/generate"),

                // -- TaxonomyController (1) --
                new Endpoint("POST", "/api/taxonomy/resolve"),

                // -- VscodeExtensionController (1) --
                new Endpoint("GET", "/api/system/vscode-extension")
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("allProtectedEndpoints")
    @DisplayName("X-API-Keyなしなら例外なく401")
    void everyProtectedEndpoint_returns401WithoutApiKey(Endpoint endpoint) throws Exception {
        mockMvc.perform(request(HttpMethod.valueOf(endpoint.method()), endpoint.path()))
                .andExpect(status().isUnauthorized());
    }

    // =====================================================================================
    // (a2) issue #564: Authorizationヘッダーの検証済みJWTをX-API-Keyの代替として受理する
    //
    // ApiKeyAuthFilterがX-API-Key・JWTのどちらも欠けている場合にのみ401を返すことを検証する。
    // (a)の401網羅がAuthorizationヘッダーを送らずX-API-Keyのみで判定しているのに対し、
    // こちらはBearerトークンだけで(X-API-Key無しで)通ることを見る。上のPUBLIC_AUTH_PATHSと
    // 同じ理由で/api/healthとPUBLIC_AUTH_PATHSは対象外。
    // =====================================================================================

    @Test
    @DisplayName("有効なAuthorization: Bearer JWTがあればX-API-Key無しでも401にならない")
    void 有効なBearerトークンならAPIキー無しでも401にならない() throws Exception {
        when(jwtDecoder.decode("valid-jwt")).thenReturn(JwtTestFixtures.jwt("keycloak-sub-1", "user"));

        mockMvc.perform(request(HttpMethod.GET, "/api/metadata/post-statuses")
                        .header("Authorization", "Bearer valid-jwt"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }

    @Test
    @DisplayName("不正なJWTかつX-API-Key無しなら401(JWT経路はX-API-Key経路を弱体化しない)")
    void 不正なBearerトークンかつAPIキー無しは401のまま() throws Exception {
        // NimbusJwtDecoderが実際に不正/期限切れトークンで投げるのはBadJwtException(JwtExceptionの
        // サブタイプ)。JwtAuthenticationProviderはBadJwtExceptionをInvalidBearerTokenException
        // (401)へ変換するが、より汎用的なJwtExceptionはAuthenticationServiceException(実装エラー
        // 扱い)へ変換されてしまうため、実挙動に忠実になるようBadJwtExceptionでスタブする。
        when(jwtDecoder.decode("invalid-jwt")).thenThrow(new BadJwtException("invalid token"));

        mockMvc.perform(request(HttpMethod.GET, "/api/metadata/post-statuses")
                        .header("Authorization", "Bearer invalid-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("X-API-Keyでの既存アクセスは、JWT検証機構の追加後も引き続き成功する(VSCode拡張の継続利用)")
    void 既存のAPIキー経路はJWT追加後も引き続き成功する() throws Exception {
        mockMvc.perform(request(HttpMethod.GET, "/api/metadata/post-statuses")
                        .header(API_KEY_HEADER, TEST_API_KEY))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }

    // =====================================================================================
    // (b) requireAdmin()で保護された代表的なエンドポイントの403検証(実HTTP)
    // =====================================================================================

    @Test
    @DisplayName("GET /api/audit-logs: admin以外のactorは403")
    void auditLogs_admin以外は403() throws Exception {
        mockMvc.perform(request(HttpMethod.GET, "/api/audit-logs")
                        .header(API_KEY_HEADER, TEST_API_KEY)
                        .header(ACTOR_ID_HEADER, "1")
                        .header(ACTOR_ROLE_HEADER, "editor"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/audit-logs: adminなら403にならない")
    void auditLogs_adminなら403にならない() throws Exception {
        mockMvc.perform(request(HttpMethod.GET, "/api/audit-logs")
                        .header(API_KEY_HEADER, TEST_API_KEY)
                        .header(ACTOR_ID_HEADER, "1")
                        .header(ACTOR_ROLE_HEADER, "admin"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
    }

    @Test
    @DisplayName("GET /api/project-users: admin以外のactorは403")
    void projectUsers_admin以外は403() throws Exception {
        mockMvc.perform(request(HttpMethod.GET, "/api/project-users")
                        .header(API_KEY_HEADER, TEST_API_KEY)
                        .header(ACTOR_ID_HEADER, "1")
                        .header(ACTOR_ROLE_HEADER, "editor"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/project-users: adminなら403にならない")
    void projectUsers_adminなら403にならない() throws Exception {
        mockMvc.perform(request(HttpMethod.GET, "/api/project-users")
                        .header(API_KEY_HEADER, TEST_API_KEY)
                        .header(ACTOR_ID_HEADER, "1")
                        .header(ACTOR_ROLE_HEADER, "admin"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
    }

    @Test
    @DisplayName("GET /api/sites/{siteId}/static-content: admin以外のactorは403")
    void siteStaticContent_admin以外は403() throws Exception {
        mockMvc.perform(request(HttpMethod.GET, "/api/sites/1/static-content")
                        .header(API_KEY_HEADER, TEST_API_KEY)
                        .header(ACTOR_ID_HEADER, "1")
                        .header(ACTOR_ROLE_HEADER, "editor"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/sites/{siteId}/static-content: adminなら403にならない")
    void siteStaticContent_adminなら403にならない() throws Exception {
        mockMvc.perform(request(HttpMethod.GET, "/api/sites/1/static-content")
                        .header(API_KEY_HEADER, TEST_API_KEY)
                        .header(ACTOR_ID_HEADER, "1")
                        .header(ACTOR_ROLE_HEADER, "admin"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
    }

    // =====================================================================================
    // (c) requireProjectMemberOrAdmin(): プロジェクト間分離(実DB、issue #568の受入基準)
    // =====================================================================================

    @Test
    @DisplayName("プロジェクトメンバーシップ: 所属プロジェクトはOK、非所属プロジェクトは403、adminは所属不問でOK")
    void projectMembership_プロジェクト間の分離を検証する() throws Exception {
        Project projectA = new Project();
        projectA.setName("Project A");
        projectA.setSlug("project-a-" + System.nanoTime());
        projectA = projectRepository.save(projectA);

        Project projectB = new Project();
        projectB.setName("Project B");
        projectB.setSlug("project-b-" + System.nanoTime());
        projectB = projectRepository.save(projectB);

        User user1 = new User();
        user1.setEmail("member-" + System.nanoTime() + "@example.com");
        user1.setPasswordHash("dummy-hash");
        user1.setRole("user");
        user1 = userRepository.save(user1);

        ProjectUser membership = new ProjectUser();
        membership.setProjectId(projectA.getId());
        membership.setUserId(user1.getId());
        membership.setWpRole("editor");
        projectUserRepository.save(membership);

        // User1(プロジェクトAのメンバー)がプロジェクトAのエンドポイントへアクセス -> 403にならない
        mockMvc.perform(request(HttpMethod.GET, "/api/projects/" + projectA.getId() + "/custom-tags")
                        .header(API_KEY_HEADER, TEST_API_KEY)
                        .header(ACTOR_ID_HEADER, String.valueOf(user1.getId()))
                        .header(ACTOR_ROLE_HEADER, "user"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));

        // User1がプロジェクトB(非所属)のエンドポイントへアクセス -> 403
        mockMvc.perform(request(HttpMethod.GET, "/api/projects/" + projectB.getId() + "/custom-tags")
                        .header(API_KEY_HEADER, TEST_API_KEY)
                        .header(ACTOR_ID_HEADER, String.valueOf(user1.getId()))
                        .header(ACTOR_ROLE_HEADER, "user"))
                .andExpect(status().isForbidden());

        // adminはプロジェクトB(User1は非所属)でも403にならない(admin全プロジェクト横断バイパス)
        mockMvc.perform(request(HttpMethod.GET, "/api/projects/" + projectB.getId() + "/custom-tags")
                        .header(API_KEY_HEADER, TEST_API_KEY)
                        .header(ACTOR_ID_HEADER, String.valueOf(user1.getId()))
                        .header(ACTOR_ROLE_HEADER, "admin"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
    }
}
