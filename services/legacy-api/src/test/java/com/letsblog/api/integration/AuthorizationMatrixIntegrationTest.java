package com.letsblog.api.integration;

import com.letsblog.api.client.ProjectServiceClient;
import com.letsblog.api.domain.ProjectUser;
import com.letsblog.api.domain.User;
import com.letsblog.api.repository.ProjectUserRepository;
import com.letsblog.api.repository.UserRepository;
import com.letsblog.common.testfixtures.AuthorizationMatrixContract;
import com.letsblog.common.testfixtures.AuthorizationMatrixContract.Endpoint;
import com.letsblog.common.testfixtures.JwtTestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
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

    /** {@code users.enabled}はidentity-serviceが書き手のため、テストからはJDBCで直接更新する(#816)。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    // =====================================================================================
    // (a) 全エンドポイント(health・公開パスを除く)の401網羅
    //
    // services/legacy-api/src/main/java/com/letsblog/api/controller/ に**現在残っている**
    // エンドポイントから、SecurityConfigのPUBLIC_PATHS(health・auth/setup・auth/setup-status)と
    // #805でサービス間内部ブリッジ(/api/internal/**)17件も一覧に加えた。gatewayのルート表には
    // 無いが、PUBLIC_PATHSにも入っていないため認証は必須で、実際401を返す。除外していたのは
    // legacy-apiだけで、他サービスは元から含めていた。
    //
    // Phase 19のサービス抽出でコントローラがlegacy-apiから次々と移設されたため、この一覧は
    // 大きく陳腐化していた(issue #731)。実体の無いパスとして残っていたのは計107件で、
    // 内訳は #574(ai)28件・#576(content)24件・#577(project)29件・#707/#708/#712(publishing)26件。
    // media(#573)・analytics(#578)・platform(#693〜#696)の移設分は、当時この一覧からは
    // 削除済みで「対象外」コメントとしてのみ残っていた(そのコメントも本Issueで整理した)。
    // SecurityConfigのanyRequest().authenticated()は
    // コントローラの有無に関わらず全パスへ適用されるため、これらは「壊れずに通り続ける」一方で
    // 「このコントローラのこのエンドポイントは認証必須」というテストの意図を満たしていなかった。
    //
    // 削除した107件は、移設先サービスの同名テスト(#772で8サービスすべてに追加)がすべて
    // 担当している。担保が失われていないことは移設先の一覧と突き合わせて確認済み。
    //
    // パスパラメータには存在確認不要な適当な値(1等)を埋める。Authorizationヘッダーの
    // 有無だけでSecurityConfigが401を返すため、リクエストボディ/クエリパラメータの妥当性は問わない。
    // =====================================================================================

    static Stream<Endpoint> allProtectedEndpoints() {
        return Stream.of(
                // -- AiController (3) --
                new Endpoint("POST", "/api/ai/image"),
                new Endpoint("GET", "/api/ai/image-options"),
                new Endpoint("POST", "/api/projects/1/ai/generate-image-prompt"),

                // -- ProjectAiModelController (6) --
                new Endpoint("GET", "/api/projects/1/ai-models/image/provider"),
                new Endpoint("PUT", "/api/projects/1/ai-models/image/provider/selection"),
                new Endpoint("GET", "/api/projects/1/ai-models/comfyui/checkpoints"),
                new Endpoint("PUT", "/api/projects/1/ai-models/comfyui/checkpoints/selection"),
                new Endpoint("POST", "/api/projects/1/ai-models/comfyui/checkpoints/install"),
                new Endpoint("DELETE", "/api/projects/1/ai-models/comfyui/checkpoints/1"),

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

                // -- ProjectController (9) --
                new Endpoint("PUT", "/api/projects/1/css-selector-prefix"),
                new Endpoint("PUT", "/api/projects/1/image-generation-prompt-defaults"),
                new Endpoint("PUT", "/api/projects/1/image-generation-size-defaults"),
                new Endpoint("PUT", "/api/projects/1/article-image-resize-default"),
                new Endpoint("PUT", "/api/projects/1/image-content-filter-settings"),
                new Endpoint("GET", "/api/projects/1/users"),
                new Endpoint("POST", "/api/projects/1/users"),
                new Endpoint("PUT", "/api/projects/1/users/1"),
                new Endpoint("DELETE", "/api/projects/1/users/1"),

                // -- ProjectUserController (1) --
                new Endpoint("GET", "/api/project-users"),

                // -- サービス間内部ブリッジ (17、issue #805で追加) --
                // gatewayのルート表に無く外部からは到達できないが、SecurityConfigの
                // PUBLIC_PATHSにも入っていないため**認証は必須**で、実際401を返す。
                // 他サービス(ai/content/project/publishing/media)は元から一覧に含めており、
                // legacy-apiだけが除外していた。#805の契約テストがこの不一致を検出した。
                // 呼び出し元はいずれもBearerトークンを転送する(docs/SYNC_SERVICE_CALLS.md参照)。
                new Endpoint("GET", "/api/internal/analytics/projects/1"),
                new Endpoint("GET", "/api/internal/analytics/projects/1/members/1"),
                new Endpoint("GET", "/api/internal/project/projects/1/members/1"),
                new Endpoint("POST", "/api/internal/project/project-users/1/sites/1/reconcile-roles"),
                new Endpoint("GET", "/api/internal/project/user-site-authors/1/1"),
                new Endpoint("POST", "/api/internal/project/user-site-authors"),
                new Endpoint("GET", "/api/internal/project/projects/1/article-image-long-edge-px"),
                new Endpoint("GET", "/api/internal/content/projects/1/members/1"),
                new Endpoint("GET", "/api/internal/content/roles"),
                new Endpoint("GET", "/api/internal/content/tag-design/PLANTUML"),
                new Endpoint("GET", "/api/internal/content/projects/1/slug"),
                new Endpoint("GET", "/api/internal/content/sites/by-key/site-key"),
                new Endpoint("GET", "/api/internal/content/sites"),
                new Endpoint("GET", "/api/internal/ai/projects/1/github-access"),
                new Endpoint("GET", "/api/internal/ai/projects/1/members/1"),
                new Endpoint("GET", "/api/internal/ai/system-settings/brave-search-api-key"),
                new Endpoint("GET", "/api/internal/ai/llm-config"));
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

        // legacy-apiに現存するエンドポイントを使う。MetadataControllerは#576でcontent-serviceへ
        // 移設済みで、そのパスでは未マップの404を見て「401ではない」と判定してしまい、
        // 有効なJWTがハンドラまで届いたことの根拠にならない(issue #731)。
        mockMvc.perform(request(HttpMethod.GET, "/api/project-users")
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

        mockMvc.perform(request(HttpMethod.GET, "/api/project-users")
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

    // ---------------------------------------------- 無効化ユーザーの発行済みトークン(issue #816)

    /**
     * 無効化しても発行済みのアクセストークンは失効しない(Keycloakが止めるのは新規発行だけ)。
     * #816以前は{@code CurrentActorService}が{@code enabled}を参照していなかったため、
     * 無効化直後のユーザーは{@code accessTokenLifespan}(既定300秒)の間APIを通せた。
     *
     * <p>legacy-apiだけ個別の修正が必要だった理由: 他の8サービスは
     * {@code GET /api/identity/me}経由で操作者を解決するためidentity-service側の修正で塞がるが、
     * legacy-apiは共有スキーマの{@code users}テーブルを自前で参照している(#786参照)。
     */
    @Test
    @DisplayName("無効化されたadminは操作者として解決されない(issue #816)")
    void 無効化adminは403() throws Exception {
        User admin = persistUser("admin");

        // enabledはidentity-serviceが書き手の列で、legacy-api側のマッピングは
        // insertable=false, updatable=false で不変にしてある(User.enabledのJavadoc参照)。
        // そのためエンティティ経由では無効化できない。identity-serviceがUPDATEした状態を
        // 再現するためJDBCで直接落とす。この書き方自体が「legacy-apiは読むだけ」という
        // 契約の実証になっている。
        jdbcTemplate.update("UPDATE users SET enabled = FALSE WHERE id = ?", admin.getId());
        // 本クラスは@Transactionalなので、JDBCの更新はJPAの永続化コンテキストに反映されない。
        // クリアしないと後続のfindByKeycloakSubがキャッシュ済みのenabled=trueを返す。
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(request(HttpMethod.GET, "/api/project-users")
                        .with(JwtTestFixtures.jwtRequestPostProcessor(admin.getKeycloakSub())))
                .andExpect(status().isForbidden());
    }

    /** 有効なユーザーは従来どおり(無効化の判定が常時弾いていないことの確認)。 */
    @Test
    @DisplayName("有効なadminは従来どおり通る(issue #816)")
    void 有効adminは403にならない() throws Exception {
        User admin = persistUser("admin");

        assertThat(admin.isEnabled())
                .as("persistUserが作るユーザーはenabled=true(DB既定値と揃えたフィールド初期値)")
                .isTrue();

        mockMvc.perform(request(HttpMethod.GET, "/api/project-users")
                        .with(JwtTestFixtures.jwtRequestPostProcessor(admin.getKeycloakSub())))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
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
                "legacy-api", allProtectedEndpoints().toList());
    }
}
