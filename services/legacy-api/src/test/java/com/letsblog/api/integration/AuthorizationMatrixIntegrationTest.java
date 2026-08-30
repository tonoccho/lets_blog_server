package com.letsblog.api.integration;

import com.letsblog.api.client.ProjectServiceClient;
import com.letsblog.api.domain.ProjectUser;
import com.letsblog.api.domain.User;
import com.letsblog.api.repository.ProjectUserRepository;
import com.letsblog.api.repository.UserRepository;
import com.letsblog.common.testfixtures.JwtTestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #568: 認可マトリクスの整備に伴う統合テスト。issue #566で{@code ApiKeyAuthFilter}
 * (旧ヘッダベースのAPIキー認証)を撤去したことに伴い、認証はKeycloakのJWTのみを情報源とする
 * モデルへ全面移行した。
 *
 * <p>現行の認可モデルは2層構造。
 * <ol>
 *   <li>{@link com.letsblog.api.config.SecurityConfig} — 有効なKeycloak JWT(Bearerトークン)が
 *       無ければ、コントローラに到達する前に401を返す({@code /api/health}と一部の公開パスを
 *       除く全{@code /api/**}が対象)。</li>
 *   <li>コントローラメソッドが呼ぶ{@code AdminAuthorizationService.requireAdmin()} /
 *       {@code requireProjectMemberOrAdmin(projectId)} — 満たさなければ403({@link
 *       com.letsblog.api.service.ForbiddenException}を{@code GlobalExceptionHandler}が403へ変換)。</li>
 * </ol>
 *
 * <p>本クラスは3点を検証する。
 * <ul>
 *   <li>(a) {@code docs/AUTHORIZATION_MATRIX.md}に列挙した全エンドポイント(health・公開パスを除く)
 *       について、Authorizationヘッダーなしでは例外なく401になること。</li>
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

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    /**
     * requireProjectMemberOrAdmin()の検証に使うProjectApiKeyController
     * (/api/projects/{id}/api-keys/github-token)は、認可を通過した後にproject-serviceの内部ブリッジを
     * 呼ぶ。認可判定そのものが本テストの関心事のため、その後続呼び出しはモックへ差し替える
     * (project-serviceのコンテナが起動していない環境でも「403にならないこと」を安定して検証するため)。
     */
    @MockitoBean
    private ProjectServiceClient projectServiceClient;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectUserRepository projectUserRepository;

    // =====================================================================================
    // (a) 全エンドポイント(health・公開パスを除く)の401網羅
    //
    // services/legacy-api/src/main/java/com/letsblog/api/controller/ の30ファイル・179エンドポイント
    // (#572でAuditLogController/OperationLogController/FrontendErrorLogControllerの3ファイル・
    // 7エンドポイントをlog-writerサービスへ移設した後、#566でAuthControllerのログイン・2FA・
    // パスワードリセット系8エンドポイントを撤去し、#688でAuthControllerのセルフサインアップ
    // 1エンドポイントを撤去した後の数)から、SecurityConfigのPUBLIC_PATHS
    // (health・auth/setup・auth/setup-status)を除いた176件を列挙する。
    // パスパラメータには存在確認不要な適当な値(1、"slug"等)を埋める。Authorizationヘッダーの
    // 有無だけでSecurityConfigが401を返すため、リクエストボディ/クエリパラメータの妥当性は問わない。
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

                // (ArticlePreviewControllerは、記事本文レンダリング(/render)が#576でcontent-serviceへ、
                // テーマCSS取得/骨格差し替え/プレビュー用投稿削除が#712でpublishing-serviceへ
                // 移設されたため対象外)

                // (AuditLogControllerは#572でlog-writerサービスへ移設したため対象外)

                // (AuthControllerのログイン・2FA・パスワードリセット系エンドポイントはissue #566で、
                // セルフサインアップ(signup)はissue #688で撤去したため対象外。
                // 残るsetup/setup-statusはSecurityConfigのPUBLIC_PATHSであり対象外)

                // (BackupControllerは#694でplatform-serviceへ移設したため対象外)

                // (CmsMediaBridgeControllerは#573 stage3でlegacy-apiに新設されたが、issue #709で
                // publishing-serviceへ移設したため対象外)

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

                // (DashboardControllerは#695でplatform-serviceへ移設したため対象外)

                // (DiagramController/GeneratedImageControllerは#573でmedia-serviceへ移設したため対象外)
                // (FrontendErrorLogControllerは#572でlog-writerサービスへ移設したため対象外)

                // -- GenerationJobController (3) --
                new Endpoint("GET", "/api/generation-jobs"),
                new Endpoint("POST", "/api/generation-jobs"),
                new Endpoint("GET", "/api/generation-jobs/1"),
                new Endpoint("PATCH", "/api/generation-jobs/1"),

                // (HealthController /api/health は公開パスのため対象外)
                // (MediaControllerは#573 stage3でmedia-serviceへ移設したため対象外)

                // -- MetadataController (2) --
                new Endpoint("GET", "/api/metadata/post-statuses"),
                new Endpoint("GET", "/api/metadata/roles"),

                // (OperationLogControllerは#572でlog-writerサービスへ移設したため対象外)

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

                // (ProjectDashboardControllerは#578でanalytics-serviceへ移設したため対象外)

                // (ProjectMediaGarbageCollectionControllerは#573 stage3でmedia-serviceへ移設したため対象外)

                // -- ProjectUserController (1) --
                new Endpoint("GET", "/api/project-users"),

                // (RenderControllerは#573でmedia-serviceへ移設したため対象外)

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

                // -- TagDesignSettingController (3) --
                new Endpoint("GET", "/api/projects/1/tag-design-settings"),
                new Endpoint("PUT", "/api/projects/1/tag-design-settings/TOC"),
                new Endpoint("POST", "/api/projects/1/tag-design-settings/TOC/generate"),

                // -- TaxonomyController (1) --
                new Endpoint("POST", "/api/taxonomy/resolve")

                // (VscodeExtensionControllerは#696でplatform-serviceへ移設したため対象外)
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("allProtectedEndpoints")
    @DisplayName("Authorizationヘッダーなしなら例外なく401")
    void everyProtectedEndpoint_returns401WithoutAuthorization(Endpoint endpoint) throws Exception {
        mockMvc.perform(request(HttpMethod.valueOf(endpoint.method()), endpoint.path()))
                .andExpect(status().isUnauthorized());
    }

    // =====================================================================================
    // (a2) issue #566: 有効なKeycloak JWTのみが認証情報として受理される
    // =====================================================================================

    @Test
    @DisplayName("有効なAuthorization: Bearer JWTがあれば401にならない")
    void 有効なBearerトークンなら401にならない() throws Exception {
        when(jwtDecoder.decode("valid-jwt")).thenReturn(JwtTestFixtures.jwt("keycloak-sub-1", "user"));

        mockMvc.perform(request(HttpMethod.GET, "/api/metadata/post-statuses")
                        .header("Authorization", "Bearer valid-jwt"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }

    @Test
    @DisplayName("不正なJWTは401")
    void 不正なBearerトークンは401() throws Exception {
        // NimbusJwtDecoderが実際に不正/期限切れトークンで投げるのはBadJwtException(JwtExceptionの
        // サブタイプ)。JwtAuthenticationProviderはBadJwtExceptionをInvalidBearerTokenException
        // (401)へ変換するが、より汎用的なJwtExceptionはAuthenticationServiceException(実装エラー
        // 扱い)へ変換されてしまうため、実挙動に忠実になるようBadJwtExceptionでスタブする。
        when(jwtDecoder.decode("invalid-jwt")).thenThrow(new BadJwtException("invalid token"));

        mockMvc.perform(request(HttpMethod.GET, "/api/metadata/post-statuses")
                        .header("Authorization", "Bearer invalid-jwt"))
                .andExpect(status().isUnauthorized());
    }

    // =====================================================================================
    // (b) requireAdmin()で保護された代表的なエンドポイントの403検証(実HTTP)
    // =====================================================================================

    // GET /api/audit-logsのrequireAdmin()検証は#572でlog-writerサービスへ移設したため、
    // このクラスの対象外(代表的なrequireAdmin()検証は下のproject-users/ai-models(image/provider)で
    // 引き続きカバーする)。

    private User persistUser(String role) {
        User user = new User();
        user.setEmail("actor-" + System.nanoTime() + "@example.com");
        user.setPasswordHash("dummy-hash");
        user.setRole(role);
        user.setKeycloakSub("keycloak-sub-" + System.nanoTime());
        return userRepository.save(user);
    }

    @Test
    @DisplayName("GET /api/project-users: admin以外のactorは403")
    void projectUsers_admin以外は403() throws Exception {
        User editor = persistUser("editor");

        mockMvc.perform(request(HttpMethod.GET, "/api/project-users")
                        .with(JwtTestFixtures.jwtRequestPostProcessor(editor.getKeycloakSub())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/project-users: adminなら403にならない")
    void projectUsers_adminなら403にならない() throws Exception {
        User admin = persistUser("admin");

        mockMvc.perform(request(HttpMethod.GET, "/api/project-users")
                        .with(JwtTestFixtures.jwtRequestPostProcessor(admin.getKeycloakSub())))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
    }

    // 元々ここはSiteStaticContentController(GET /api/sites/{siteId}/static-content)で
    // requireAdmin()を検証していたが、当該コントローラは#577でproject-serviceへ移管済みで
    // legacy-apiにはもう存在しない(このIssue以前からの既存の陳腐化であり、当時新たに
    // rewriteするにあたって発覚したため、legacy-apiに残る別のrequireAdmin()採用エンドポイント
    // であるDashboardController(/api/dashboard/service-status/detail)に差し替えていた)。
    //
    // 本Issue(#695、C10-3)でDashboardController自体もplatform-serviceへ移管されlegacy-apiには
    // もう存在しないため、同じくrequireAdmin()を最初に呼ぶProjectAiModelController
    // (/api/projects/{id}/ai-models/image/provider)へ再度差し替える。

    @Test
    @DisplayName("GET /api/projects/1/ai-models/image/provider: admin以外のactorは403")
    void imageProvider_admin以外は403() throws Exception {
        User editor = persistUser("editor");

        mockMvc.perform(request(HttpMethod.GET, "/api/projects/1/ai-models/image/provider")
                        .with(JwtTestFixtures.jwtRequestPostProcessor(editor.getKeycloakSub())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/projects/1/ai-models/image/provider: adminなら403にならない")
    void imageProvider_adminなら403にならない() throws Exception {
        User admin = persistUser("admin");

        mockMvc.perform(request(HttpMethod.GET, "/api/projects/1/ai-models/image/provider")
                        .with(JwtTestFixtures.jwtRequestPostProcessor(admin.getKeycloakSub())))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
    }

    // =====================================================================================
    // (c) requireProjectMemberOrAdmin(): プロジェクト間分離(実DB、issue #568の受入基準)
    // =====================================================================================

    @Test
    @DisplayName("プロジェクトメンバーシップ: 所属プロジェクトはOK、非所属プロジェクトは403、adminは所属不問でOK")
    void projectMembership_プロジェクト間の分離を検証する() throws Exception {
        // requireProjectMemberOrAdmin()はproject_users(このテストが検証するもの)のみを見ており、
        // projectsテーブルの実在は問わない。projects本体の所有権はproject-serviceへ移った(issue #577)
        // ため、legacy-apiのローカルProjectRepositoryはもう存在しない。ここではproject_usersの
        // projectId外部キー相当として一意なIDを直接払い出すだけでよい。
        long projectAId = System.nanoTime();
        long projectBId = projectAId + 1;

        User user1 = persistUser("user");

        ProjectUser membership = new ProjectUser();
        membership.setProjectId(projectAId);
        membership.setUserId(user1.getId());
        membership.setWpRole("editor");
        projectUserRepository.save(membership);

        // User1(プロジェクトAのメンバー)がプロジェクトAのエンドポイントへアクセス -> 403にならない
        // (issue #576でProjectCustomTagController(/api/projects/{id}/custom-tags)はcontent-serviceへ、
        // issue #577 stage1でTagDesignSettingController(/api/projects/{id}/tag-design-settings)は
        // project-serviceへ、issue #712でArticlePreviewController(/api/projects/{id}/preview/theme-css)は
        // publishing-serviceへ移管されたため、legacy-api側に残るrequireProjectMemberOrAdmin採用
        // エンドポイントであるProjectApiKeyController(/api/projects/{id}/api-keys/github-token)で
        // 検証する。認可チェックは後続のプロジェクト参照(project-serviceへの内部ブリッジ、下記で
        // モック)より先に行われるため、projectId自体がproject-serviceに実在しなくても403判定の
        // 検証には影響しない)。
        when(projectServiceClient.getGithubToken(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new ProjectServiceClient.GithubTokenBridge(false, null));

        mockMvc.perform(request(HttpMethod.GET, "/api/projects/" + projectAId + "/api-keys/github-token")
                        .with(JwtTestFixtures.jwtRequestPostProcessor(user1.getKeycloakSub())))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));

        // User1がプロジェクトB(非所属)のエンドポイントへアクセス -> 403
        mockMvc.perform(request(HttpMethod.GET, "/api/projects/" + projectBId + "/api-keys/github-token")
                        .with(JwtTestFixtures.jwtRequestPostProcessor(user1.getKeycloakSub())))
                .andExpect(status().isForbidden());

        // adminはプロジェクトB(User1は非所属)でも403にならない(admin全プロジェクト横断バイパス)
        User admin = persistUser("admin");
        mockMvc.perform(request(HttpMethod.GET, "/api/projects/" + projectBId + "/api-keys/github-token")
                        .with(JwtTestFixtures.jwtRequestPostProcessor(admin.getKeycloakSub())))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
    }
}
