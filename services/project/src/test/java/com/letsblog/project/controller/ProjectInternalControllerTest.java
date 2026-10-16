package com.letsblog.project.controller;

import com.letsblog.project.client.IdentityBridgeClient;
import com.letsblog.project.cms.CmsType;
import com.letsblog.project.domain.Project;
import com.letsblog.project.domain.Site;
import com.letsblog.project.dto.GithubTokenBridgeResponse;
import com.letsblog.project.dto.ProjectBridgeResponse;
import com.letsblog.project.dto.SiteBridgeResponse;
import com.letsblog.project.service.GithubTokenNotConfiguredException;
import com.letsblog.project.service.ProjectNotFoundException;
import com.letsblog.project.testsupport.ProjectApp;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProjectInternalControllerの回帰テスト(issue #577 stage3)。他サービスがこのエンドポイント経由で
 * プロジェクト/サイトの基本情報を取得できることを検証する。
 *
 * <p>issue #1818: ProjectService・IdentityBridgeClient・CurrentActorService・CredentialCipher は実物。
 * identity-service の代わりにフェイクHTTPサーバーが応答し、差し替えるのはDBのリポジトリだけ。
 */
class ProjectInternalControllerTest {

    private final ProjectApp app = new ProjectApp();

    @AfterEach
    void close() {
        app.close();
    }

    private ProjectInternalController controller() {
        return new ProjectInternalController(app.projectService, app.world.siteRepository,
                app.world.identityBridgeClient, app.world.currentActorService, app.world.credentialCipher);
    }

    private Project buildProject(Long id) {
        Project project = new Project();
        project.setId(id);
        project.setName("テストプロジェクト");
        project.setSlug("test-project");
        project.setMasterEnvironment("test");
        project.setLocalSiteId(10L);
        project.setTestSiteId(20L);
        project.setProductionSiteId(null);
        project.setGithubRepository("owner/repo");
        when(app.projectRepository.findById(id)).thenReturn(Optional.of(project));
        return project;
    }

    private Site buildSite(Long id, String siteKey) {
        Site site = new Site();
        site.setId(id);
        site.setSiteKey(siteKey);
        site.setName("My Blog");
        site.setBaseUrl("https://example.com");
        site.setCmsType(CmsType.WORDPRESS);
        site.setManagedWordpress(true);
        site.setWpSlug("my-blog");
        return site;
    }

    @Test
    void project_基本情報を返す() {
        buildProject(1L);

        ProjectBridgeResponse response = controller().project(1L);

        assertEquals(1L, response.id());
        assertEquals("test-project", response.slug());
        assertEquals("test", response.masterEnvironment());
        assertEquals(10L, response.localSiteId());
        assertEquals(20L, response.testSiteId());
        assertNull(response.productionSiteId());
        assertEquals("owner/repo", response.githubRepository());
    }

    @Test
    void project_存在しなければNotFound() {
        when(app.projectRepository.findById(9L)).thenReturn(Optional.empty());

        assertThrows(ProjectNotFoundException.class, () -> controller().project(9L));
    }

    @Test
    void githubToken_保存済みなら暗号化済みの値を返し未設定ならconfiguredがfalse() {
        Project project = buildProject(1L);
        project.setGithubTokenEncrypted(new byte[] {1, 2, 3});
        Project empty = buildProject(2L);
        Project blank = buildProject(3L);
        blank.setGithubTokenEncrypted(new byte[0]);

        GithubTokenBridgeResponse set = controller().githubToken(1L);
        assertTrue(set.configured());
        assertArrayEquals(new byte[] {1, 2, 3}, set.encryptedToken());
        assertFalse(controller().githubToken(2L).configured(), "null は未設定");
        assertFalse(controller().githubToken(3L).configured(), "空は未設定");
        assertNull(empty.getGithubTokenEncrypted());
    }

    @Test
    void setGithubToken_暗号化済みの値を保存し_clearで消す() {
        Project project = buildProject(1L);

        controller().setGithubToken(1L, new com.letsblog.project.dto.SetGithubTokenBridgeRequest(new byte[] {9, 8}));
        assertArrayEquals(new byte[] {9, 8}, project.getGithubTokenEncrypted());

        controller().clearGithubToken(1L);
        assertNull(project.getGithubTokenEncrypted());
        verify(app.projectRepository, org.mockito.Mockito.times(2)).save(project);
    }

    @Test
    void projectIdForSite_所属プロジェクトIDを返す() {
        Project project = buildProject(1L);
        when(app.projectRepository.findByLocalSiteIdOrTestSiteIdOrProductionSiteId(10L, 10L, 10L))
                .thenReturn(Optional.of(project));

        assertEquals(1L, controller().projectIdForSite(10L).projectId());
    }

    @Test
    void projectIdForSite_未紐付けならnull() {
        when(app.projectRepository.findByLocalSiteIdOrTestSiteIdOrProductionSiteId(99L, 99L, 99L))
                .thenReturn(Optional.empty());

        assertNull(controller().projectIdForSite(99L).projectId());
    }

    @Test
    void site_存在すれば200で返す() {
        when(app.world.siteRepository.findById(10L)).thenReturn(Optional.of(buildSite(10L, "my-blog")));

        ResponseEntity<SiteBridgeResponse> response = controller().site(10L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("my-blog", response.getBody().siteKey());
        assertEquals(true, response.getBody().managedWordpress());
    }

    @Test
    void site_存在しなければ404() {
        when(app.world.siteRepository.findById(99L)).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, controller().site(99L).getStatusCode());
    }

    @Test
    void siteByKey_存在すれば200で返す() {
        when(app.world.siteRepository.findBySiteKey("my-blog")).thenReturn(Optional.of(buildSite(10L, "my-blog")));

        ResponseEntity<SiteBridgeResponse> response = controller().siteByKey("my-blog");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(10L, response.getBody().id());
    }

    @Test
    void siteByKey_存在しなければ404() {
        when(app.world.siteRepository.findBySiteKey("unknown")).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, controller().siteByKey("unknown").getStatusCode());
    }

    @Test
    void sites_全件を返す() {
        when(app.world.siteRepository.findAll()).thenReturn(List.of(buildSite(10L, "a"), buildSite(20L, "b")));

        assertEquals(2, controller().sites().size());
    }

    @Test
    void projectEligibility_本番サイトがあればtrue_無ければfalse() {
        Project withProduction = buildProject(1L);
        withProduction.setProductionSiteId(30L);
        buildProject(2L);

        assertTrue(controller().projectEligibility(1L).hasProductionSite());
        assertFalse(controller().projectEligibility(2L).hasProductionSite());
    }

    @Test
    void accessibleSiteIds_所属プロジェクトのサイトIDへ展開する() {
        app.world.actingAsUser(5L);
        app.world.respond("GET", "/api/internal/identity/users/5/project-ids", List.of(1L));
        Project project = buildProject(1L);
        when(app.projectRepository.findAllById(any())).thenReturn(List.of(project));

        assertEquals(java.util.Set.of(10L, 20L), java.util.Set.copyOf(controller().accessibleSiteIds(5L)));
        assertEquals("Bearer token-5", app.world.http.requests("GET", "/api/internal/identity/users/5/project-ids")
                .get(0).header("Authorization"));
    }

    @Test
    void accessibleSiteIds_所属プロジェクトが無ければ空() {
        app.world.actingAsUser(5L);
        app.world.respond("GET", "/api/internal/identity/users/5/project-ids", List.of());

        assertEquals(List.of(), controller().accessibleSiteIds(5L));
        verify(app.projectRepository, org.mockito.Mockito.never()).findAllById(any());
    }

    @Test
    void githubAccess_プロジェクト自身のトークンを優先して復号して返す() {
        Project project = buildProject(1L);
        project.setGithubTokenEncrypted(app.world.credentialCipher.encrypt("project-token"));

        ProjectInternalController.GithubAccessResponse response = controller().githubAccess(1L, 5L);

        assertEquals("project-token", response.token());
        assertEquals("owner", response.owner());
        assertEquals("repo", response.repo());
        assertTrue(app.world.http.requests("GET", "/api/internal/identity/users/5/github-token").isEmpty(),
                "プロジェクトのトークンがあれば identity-service へ問い合わせない");
    }

    @Test
    void githubAccess_プロジェクトのトークンが無ければ操作者本人のトークンへフォールバックする() {
        app.world.actingAsUser(5L);
        buildProject(1L);
        app.world.respond("GET", "/api/internal/identity/users/5/github-token",
                new IdentityBridgeClient.UserGithubToken(true, app.world.credentialCipher.encrypt("user-token")));

        ProjectInternalController.GithubAccessResponse response = controller().githubAccess(1L, 5L);

        assertEquals("user-token", response.token());
        assertEquals("Bearer token-5", app.world.http.requests("GET", "/api/internal/identity/users/5/github-token")
                .get(0).header("Authorization"));
    }

    @Test
    void githubAccess_空のプロジェクトトークンは未設定として本人のトークンへフォールバックする() {
        app.world.actingAsUser(5L);
        Project project = buildProject(1L);
        project.setGithubTokenEncrypted(new byte[0]);
        app.world.respond("GET", "/api/internal/identity/users/5/github-token",
                new IdentityBridgeClient.UserGithubToken(true, app.world.credentialCipher.encrypt("user-token")));

        assertEquals("user-token", controller().githubAccess(1L, 5L).token());
    }

    @Test
    void githubAccess_トークンが未設定なら専用例外で原因を示す() {
        app.world.actingAsUser(5L);
        buildProject(1L);
        app.world.respond("GET", "/api/internal/identity/users/5/github-token",
                new IdentityBridgeClient.UserGithubToken(false, null));

        GithubTokenNotConfiguredException e = assertThrows(
                GithubTokenNotConfiguredException.class, () -> controller().githubAccess(1L, 5L));

        assertEquals("ユーザーの GitHub トークンが設定されていません", e.getMessage());
        assertEquals("GITHUB_TOKEN_NOT_CONFIGURED", GithubTokenNotConfiguredException.REASON);
    }

    @Test
    void githubAccess_リポジトリが未設定なら理由つきで拒否する() {
        Project project = buildProject(1L);
        project.setGithubRepository(null);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> controller().githubAccess(1L, 5L));

        assertTrue(e.getMessage().contains("GitHubリポジトリ"));
    }
}
