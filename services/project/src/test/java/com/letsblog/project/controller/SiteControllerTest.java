package com.letsblog.project.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.project.cms.ConnectionCheckResult;
import com.letsblog.project.cms.CmsType;
import com.letsblog.project.cms.LetsblogPluginStatus;
import com.letsblog.project.cms.LetsblogSyncResult;
import com.letsblog.project.cms.ProvisioningResult;
import com.letsblog.project.client.ContentBridgeClient.SyncPayload;
import com.letsblog.project.config.GlobalExceptionHandler;
import com.letsblog.project.domain.LetsblogSyncStatus;
import com.letsblog.project.domain.Project;
import com.letsblog.project.domain.Site;
import com.letsblog.project.dto.AdoptWordPressSiteRequest;
import com.letsblog.project.dto.CreateManagedWordPressSiteRequest;
import com.letsblog.project.dto.GenerationJobResponse;
import com.letsblog.project.dto.LetsblogSyncState;
import com.letsblog.project.dto.SiteDetailResponse;
import com.letsblog.project.dto.SiteRegisterRequest;
import com.letsblog.project.dto.SiteResponse;
import com.letsblog.project.cms.WpCliInstallResult;
import com.letsblog.project.service.ForbiddenException;
import com.letsblog.project.testsupport.FakeHttpServer;
import com.letsblog.project.testsupport.ProjectApp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SiteControllerの回帰テスト(issue #577 stage2、legacy-apiから移設)。
 *
 * <p>issue #1818: SiteService・WordPressSiteProvisioningService・ProjectService・LetsblogSyncService・
 * ManagedSiteProvisioningJobStarter・SshKeyGenerationService・AdminAuthorizationService は実物。identity-service・
 * ai-service・provision-agent・publishing-service(CMS ブリッジ)の代わりにフェイクHTTPサーバーが応答し、
 * 差し替えるのはDBのリポジトリとMQ({@code RabbitTemplate})だけ。
 */
class SiteControllerTest {

    private static final String CMS = "/api/internal/project/cms/";
    private static final String PROVISION_URL = "https://localhost/sites/my-site";

    private final ProjectApp app = new ProjectApp();

    @AfterEach
    void close() {
        app.close();
    }

    private SiteController controller() {
        return new SiteController(app.world.siteService, app.world.adminAuthorizationService,
                app.wordPressSiteProvisioningService, app.sshKeyGenerationService, app.projectService,
                app.letsblogSyncService, app.managedSiteProvisioningJobStarter);
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(controller()).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private void asNonAdmin() {
        app.world.actingAsUser(5L);
    }

    private static SiteRegisterRequest registerRequest() {
        return new SiteRegisterRequest("Name", "my-site", CmsType.WORDPRESS,
                Map.of("baseUrl", "https://example.com", "transport", "AGENT", "username", "u"), null);
    }

    private static CreateManagedWordPressSiteRequest managedRequest() {
        return new CreateManagedWordPressSiteRequest(
                "Name", "my-site", "Title", "admin", "admin@example.com", "pw", "ja", null);
    }

    private Site registeredSite() {
        Site site = new Site();
        site.setId(1L);
        site.setSiteKey("my-site");
        when(app.world.siteRepository.findBySiteKey("my-site")).thenReturn(Optional.of(site));
        return site;
    }

    private void provisioningAnswers() {
        app.world.registrationSucceeds();
        app.world.respond("POST", "/provision", Map.of("url", PROVISION_URL, "adminUser", "admin"));
        app.world.http.on("POST", "/deprovision", FakeHttpServer.Response.empty(200));
        app.world.http.on("POST", "/sync", FakeHttpServer.Response.empty(200));
    }

    @Test
    void register_登録できる() {
        app.world.actingAsAdmin(1L);
        app.world.registrationSucceeds();

        ResponseEntity<SiteResponse> response = controller().register(registerRequest());

        assertEquals(201, response.getStatusCode().value());
        assertEquals("my-site", response.getBody().siteKey());
        assertEquals(1, app.world.http.requests("POST", CMS + "provision").size());
    }

    @Test
    void getDetail_admin権限があれば設定値を返す() {
        app.world.actingAsAdmin(1L);
        app.world.addSite(1L, "サイト");

        SiteDetailResponse detail = controller().getDetail(1L);

        assertEquals("https://site1.example.com", detail.credentials().get("baseUrl"));
    }

    @Test
    void getDetail_admin権限がなければForbidden() {
        asNonAdmin();
        app.world.addSite(1L, "サイト");

        assertThrows(ForbiddenException.class, () -> controller().getDetail(1L));
    }

    @Test
    void delete_admin権限があれば削除できる() {
        app.world.actingAsAdmin(1L);
        Site site = app.world.addManagedSite(1L, "my-site", "wp_my-site");
        app.world.http.on("POST", "/deprovision", FakeHttpServer.Response.empty(200));

        ResponseEntity<Void> response = controller().delete(1L);

        assertEquals(204, response.getStatusCode().value());
        verify(app.world.siteRepository).delete(site);
        assertEquals("my-site", app.world.bodyOf(app.world.http.requests("POST", "/deprovision").get(0)).get("slug").asText());
    }

    @Test
    void delete_admin権限がなければForbiddenで削除しない() {
        asNonAdmin();
        app.world.addManagedSite(1L, "my-site", "wp_my-site");

        assertThrows(ForbiddenException.class, () -> controller().delete(1L));

        verify(app.world.siteRepository, never()).delete(any());
        assertTrue(app.world.http.requests("POST", "/deprovision").isEmpty());
    }

    @Test
    void testConnection_成功を結果のmapで返す() {
        app.world.actingAsAdmin(1L);
        app.world.addSite(1L, "サイト");
        app.world.respond("POST", CMS + "test-connection", new ConnectionCheckResult(true, null, null, "detail"));

        Map<String, Object> response = controller().testConnection(1L);

        assertEquals("SUCCESS", response.get("connectionCheckStatus"));
        assertEquals(true, response.get("hasAdminCapability"));
        assertEquals("detail", response.get("detail"));
    }

    @Test
    void testConnection_失敗は理由つきでFAILEDを返す() {
        app.world.actingAsAdmin(1L);
        app.world.addSite(1L, "サイト");
        app.world.respond("POST", CMS + "test-connection", ConnectionCheckResult.failure("接続失敗"));

        Map<String, Object> response = controller().testConnection(1L);

        assertEquals("FAILED", response.get("connectionCheckStatus"));
        assertNull(response.get("hasAdminCapability"));
        assertEquals("接続失敗", response.get("failureReason"));
    }

    @Test
    void reprovision_admin権限があれば実行でき作成したIDを知らせる() {
        app.world.actingAsAdmin(1L);
        app.world.addSite(1L, "サイト");
        app.world.respond("POST", CMS + "provision", new ProvisioningResult("cat-1", null, "tag-1", null, "author-1", null));

        ResponseEntity<Map<String, String>> response = controller().reprovision(1L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("プロビジョニングを再実行しました。カテゴリ: cat-1 / タグ: tag-1 / 著者: author-1",
                response.getBody().get("message"));
    }

    @Test
    void reprovision_作成できなかったものは失敗_未対応と知らせる() {
        app.world.actingAsAdmin(1L);
        app.world.addSite(1L, "サイト");
        app.world.respond("POST", CMS + "provision", new ProvisioningResult(null, "e1", null, "e2", null, "e3"));

        ResponseEntity<Map<String, String>> response = controller().reprovision(1L);

        assertEquals("プロビジョニングを再実行しました。カテゴリ: 失敗 / タグ: 失敗 / 著者: 未対応または失敗",
                response.getBody().get("message"));
    }

    @Test
    void reprovision_admin以外は拒否しブリッジへ触れない() {
        asNonAdmin();
        app.world.addSite(1L, "サイト");

        assertThrows(ForbiddenException.class, () -> controller().reprovision(1L));

        assertTrue(app.world.http.requests("POST", CMS + "provision").isEmpty());
    }

    // ---- issue #830: 「認証済みなら誰でも」だった4件をadmin限定にした回帰テスト ----

    @Test
    void register_admin以外は拒否しサイトを登録しない() {
        asNonAdmin();
        app.world.registrationSucceeds();

        assertThrows(ForbiddenException.class, () -> controller().register(registerRequest()));

        verify(app.world.siteRepository, never()).save(any());
        assertTrue(app.world.http.requests("POST", CMS + "provision").isEmpty());
    }

    @Test
    void createManagedWordPress_admin権限があれば構築して201を返す() {
        app.world.actingAsAdmin(1L);
        provisioningAnswers();
        registeredSite();

        ResponseEntity<SiteResponse> response = controller().createManagedWordPress(managedRequest());

        assertEquals(201, response.getStatusCode().value());
        assertEquals("my-site", response.getBody().siteKey());
        assertEquals("my-site", app.world.bodyOf(app.world.http.requests("POST", "/provision").get(0)).get("slug").asText());
    }

    @Test
    void createManagedWordPress_admin以外は拒否しプロビジョニングしない() {
        asNonAdmin();
        provisioningAnswers();

        assertThrows(ForbiddenException.class, () -> controller().createManagedWordPress(managedRequest()));

        assertTrue(app.world.http.requests("POST", "/provision").isEmpty());
    }

    @Test
    void adoptManagedWordPress_admin権限があれば取り込んで201を返す() {
        app.world.actingAsAdmin(1L);
        provisioningAnswers();
        app.world.respond("POST", "/adopt", Map.of("url", PROVISION_URL, "adminUser", "admin"));
        Site site = registeredSite();
        when(app.world.siteRepository.findAll()).thenReturn(List.of(site));

        ResponseEntity<SiteResponse> response =
                controller().adoptManagedWordPress(new AdoptWordPressSiteRequest("Name", "my-site", "admin"));

        assertEquals(201, response.getStatusCode().value());
        assertEquals("my-site", response.getBody().siteKey());
    }

    @Test
    void adoptManagedWordPress_admin以外は拒否し取り込まない() {
        asNonAdmin();
        app.world.respond("POST", "/adopt", Map.of("url", PROVISION_URL, "adminUser", "admin"));

        assertThrows(ForbiddenException.class,
                () -> controller().adoptManagedWordPress(new AdoptWordPressSiteRequest("Name", "my-site", "admin")));

        assertTrue(app.world.http.requests("POST", "/adopt").isEmpty());
    }

    @Test
    void testConnection_admin以外は拒否し接続確認を行わない() {
        asNonAdmin();
        app.world.addSite(1L, "サイト");

        assertThrows(ForbiddenException.class, () -> controller().testConnection(1L));

        assertTrue(app.world.http.requests("POST", CMS + "test-connection").isEmpty());
    }

    // ---- SSH キーペア / wp-cli ----

    @Test
    void generateSshKeyPair_コメントを指定しなければ既定のletsblogで生成する() {
        app.world.actingAsAdmin(1L);

        var withoutBody = controller().generateSshKeyPair(null);
        var withoutComment = controller().generateSshKeyPair(new com.letsblog.project.dto.SshKeyPairRequest(null));
        var withComment = controller().generateSshKeyPair(new com.letsblog.project.dto.SshKeyPairRequest("my-key"));

        assertTrue(withoutBody.publicKeyLine().startsWith("ssh-ed25519 "));
        assertTrue(withoutBody.publicKeyLine().endsWith(" letsblog"));
        assertTrue(withoutComment.publicKeyLine().endsWith(" letsblog"));
        assertTrue(withComment.publicKeyLine().endsWith(" my-key"));
        assertTrue(withComment.privateKeyPem().contains("PRIVATE KEY"));
    }

    @Test
    void generateSshKeyPair_admin以外は拒否する() {
        asNonAdmin();

        assertThrows(ForbiddenException.class, () -> controller().generateSshKeyPair(null));
    }

    @Test
    void installWpCli_admin権限があればブリッジ経由で実行結果を返す() {
        app.world.actingAsAdmin(1L);
        app.world.addSshSite(1L, "SSHサイト");
        app.world.respond("POST", CMS + "install-wp-cli", new WpCliInstallResult("インストール完了"));

        assertEquals("インストール完了", controller().installWpCli(1L).message());
    }

    @Test
    void installWpCli_admin以外は拒否する() {
        asNonAdmin();
        app.world.addSshSite(1L, "SSHサイト");

        assertThrows(ForbiddenException.class, () -> controller().installWpCli(1L));

        assertTrue(app.world.http.requests("POST", CMS + "install-wp-cli").isEmpty());
    }

    // ---- issue #830: 一覧は所属プロジェクトに紐付くサイトだけ ----

    private void sitesInRepository() {
        Site site = app.world.addSite(1L, "サイト1");
        site.setCreatedAt(java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
        site.setUpdatedAt(java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
        when(app.world.siteRepository.findAll()).thenReturn(new java.util.ArrayList<>(List.of(site)));
    }

    @Test
    void list_adminは全件を返す() {
        app.world.actingAsAdmin(1L);
        sitesInRepository();

        assertEquals(1, controller().list(null, null).size());
    }

    @Test
    void list_非adminは所属プロジェクトのサイトだけに絞られる() {
        app.world.actingAsUser(5L);
        app.world.respond("GET", "/api/internal/identity/users/5/project-ids", List.of(3L));
        sitesInRepository();
        Project project = new Project();
        project.setId(3L);
        project.setLocalSiteId(9L);
        when(app.projectRepository.findAllById(Set.of(3L))).thenReturn(List.of(project));

        assertEquals(List.of(), controller().list(null, null));
    }

    @Test
    void list_所属プロジェクトのサイトなら残る() {
        app.world.actingAsUser(5L);
        app.world.respond("GET", "/api/internal/identity/users/5/project-ids", List.of(3L));
        sitesInRepository();
        Project project = new Project();
        project.setId(3L);
        project.setLocalSiteId(1L);
        when(app.projectRepository.findAllById(Set.of(3L))).thenReturn(List.of(project));

        assertEquals(1, controller().list(null, null).size());
    }

    /** issue #1196: 実SiteService + GlobalExceptionHandler越しに、空のnameがHTTP 400で拒否されることを確認する。 */
    private Site siteForUpdate() {
        Site site = new Site();
        site.setId(1L);
        site.setName("元の名前");
        site.setCmsType(CmsType.WORDPRESS);
        when(app.world.siteRepository.findById(1L)).thenReturn(Optional.of(site));
        when(app.world.siteRepository.save(any(Site.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return site;
    }

    @Test
    void update_空文字のnameは400で原因メッセージを返す() throws Exception {
        app.world.actingAsAdmin(1L);
        siteForUpdate();

        mockMvc().perform(put("/api/sites/1").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("サイト名は空にできません")));
    }

    @Test
    void update_有効なnameなら200で更新される() throws Exception {
        app.world.actingAsAdmin(1L);
        siteForUpdate();

        mockMvc().perform(put("/api/sites/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新しい名前\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("新しい名前"));
    }

    @Test
    void update_adminPathを送ると200で設定され応答に含まれる() throws Exception {
        app.world.actingAsAdmin(1L);
        siteForUpdate();

        mockMvc().perform(put("/api/sites/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adminPath\":\"secret-login\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adminPath").value("secret-login"));
    }

    @Test
    void update_不正なadminPathは400で保存済みの値は変わらない() throws Exception {
        app.world.actingAsAdmin(1L);
        Site site = siteForUpdate();
        site.setAdminPath("keep-me");

        mockMvc().perform(put("/api/sites/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adminPath\":\"javascript:alert(1)\"}"))
                .andExpect(status().isBadRequest());

        assertEquals("keep-me", site.getAdminPath());
        verify(app.world.siteRepository, never()).save(any());
    }

    @Test
    void update_admin以外は403で更新しない() throws Exception {
        asNonAdmin();
        Site site = siteForUpdate();

        mockMvc().perform(put("/api/sites/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新しい名前\"}"))
                .andExpect(status().isForbidden());

        assertEquals("元の名前", site.getName());
    }

    @Test
    void sshKeypair_はHTTPでも本文なしで生成できる() throws Exception {
        app.world.actingAsAdmin(1L);

        mockMvc().perform(post("/api/sites/ssh-keypair"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKeyLine").value(startsWith("ssh-ed25519 ")));
    }

    // ---- issue #1557: letsblogプラグインの導入状態 ----

    @Test
    void letsblogPluginStatus_admin権限で導入状態を返す() {
        app.world.actingAsAdmin(1L);
        app.world.addSite(1L, "サイト");
        app.world.pluginState(LetsblogPluginStatus.State.NOT_INSTALLED);

        assertEquals(LetsblogPluginStatus.State.NOT_INSTALLED, controller().letsblogPluginStatus(1L).state());
    }

    @Test
    void letsblogPluginStatus_admin以外は拒否する() {
        asNonAdmin();
        app.world.addSite(1L, "サイト");

        assertThrows(ForbiddenException.class, () -> controller().letsblogPluginStatus(1L));
        assertTrue(app.world.http.requests("POST", CMS + "letsblog-plugin-status").isEmpty());
    }

    @Test
    void installLetsblogPlugin_admin権限で再導入し導入後の状態を返す() {
        app.world.actingAsAdmin(1L);
        app.world.addSite(1L, "サイト");
        app.world.respond("POST", CMS + "install-letsblog-plugin",
                new LetsblogPluginStatus(LetsblogPluginStatus.State.INSTALLED, "1.0.0", 1));

        LetsblogPluginStatus status = controller().installLetsblogPlugin(1L);

        assertEquals(LetsblogPluginStatus.State.INSTALLED, status.state());
        assertEquals("1.0.0", status.version());
    }

    @Test
    void installLetsblogPlugin_admin以外は拒否し導入しない() {
        asNonAdmin();
        app.world.addSite(1L, "サイト");

        assertThrows(ForbiddenException.class, () -> controller().installLetsblogPlugin(1L));
        assertTrue(app.world.http.requests("POST", CMS + "install-letsblog-plugin").isEmpty());
    }

    // ---- issue #1558: letsblogプラグインへの同期状態と再同期 ----

    @Test
    void letsblogSync_admin権限で同期状態を返す() {
        app.world.actingAsAdmin(1L);
        Site site = app.world.addSite(1L, "サイト");
        site.setLetsblogSyncStatus(LetsblogSyncStatus.FAILED);
        site.setLetsblogSyncError("接続失敗");

        LetsblogSyncState state = controller().letsblogSync(1L);

        assertEquals(LetsblogSyncStatus.FAILED, state.status());
        assertEquals("接続失敗", state.error());
    }

    @Test
    void letsblogSync_admin以外は拒否する() {
        asNonAdmin();
        app.world.addSite(1L, "サイト");

        assertThrows(ForbiddenException.class, () -> controller().letsblogSync(1L));
    }

    @Test
    void resyncLetsblog_admin権限で再同期し結果の状態を返す() {
        app.world.actingAsAdmin(1L);
        app.syncableProject(7L, 1L);
        Optional<Project> owner = app.projectRepository.findById(7L);
        when(app.projectRepository.findByLocalSiteIdOrTestSiteIdOrProductionSiteId(1L, 1L, 1L)).thenReturn(owner);

        LetsblogSyncState state = controller().resyncLetsblog(1L);

        assertEquals(LetsblogSyncStatus.SYNCED, state.status());
        assertEquals("h1", state.hash());
        assertEquals(1, app.pluginSyncRequests());
    }

    @Test
    void resyncLetsblog_admin以外は拒否する() {
        asNonAdmin();
        app.syncableProject(7L, 1L);

        assertThrows(ForbiddenException.class, () -> controller().resyncLetsblog(1L));

        assertEquals(0, app.pluginSyncRequests());
    }

    // ---- issue #1479: サイト自動構築の非同期受理 ----

    @Test
    void createManagedWordPressJob_adminなら202とジョブIDを返し構築は待たない() throws Exception {
        app.world.actingAsAdmin(1L);
        app.world.jobCreated(11L, "site_provisioning");

        ResponseEntity<GenerationJobResponse> response = controller().createManagedWordPressJob(managedRequest());

        assertEquals(202, response.getStatusCode().value());
        assertEquals(11L, response.getBody().id());
        assertEquals("site_provisioning", response.getBody().type());
        assertEquals("running", response.getBody().status());
        // 日時はZ終端のUTC(issue #1539と同じ)。
        assertEquals("2026-10-09T01:02:03Z", String.valueOf(response.getBody().createdAt()));
        assertEquals(1, app.jobs.size(), "構築は実行枠へ依頼されただけ");
        assertTrue(app.world.http.requests("POST", "/provision").isEmpty(), "構築は待たない");
    }

    @Test
    void createManagedWordPressJob_admin以外は拒否しジョブを作らない() {
        asNonAdmin();

        assertThrows(ForbiddenException.class, () -> controller().createManagedWordPressJob(managedRequest()));

        assertTrue(app.world.http.requests("POST", "/api/internal/ai/generation-jobs").isEmpty());
        assertTrue(app.jobs.isEmpty());
    }
}
