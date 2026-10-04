package com.letsblog.project.controller;

import com.letsblog.project.cms.CmsType;
import com.letsblog.project.config.GlobalExceptionHandler;
import com.letsblog.project.crypto.SshKeyGenerationService;
import com.letsblog.project.dto.SiteConnectionCheckResult;
import com.letsblog.project.dto.SiteRegisterRequest;
import com.letsblog.project.dto.SiteResponse;
import com.letsblog.project.service.AdminAuthorizationService;
import com.letsblog.project.service.ForbiddenException;
import com.letsblog.project.service.ProvisioningService;
import com.letsblog.project.service.ProjectService;
import com.letsblog.project.service.SiteService;
import com.letsblog.project.service.WordPressSiteProvisioningService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.project.domain.Site;
import com.letsblog.project.repository.SiteRepository;
import java.util.Base64;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Optional;
import java.util.Set;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** SiteControllerの回帰テスト(issue #577 stage2、legacy-apiから移設)。 */
@ExtendWith(MockitoExtension.class)
class SiteControllerTest {

    @Mock
    private SiteService siteService;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;
    @Mock
    private WordPressSiteProvisioningService wordPressSiteProvisioningService;
    @Mock
    private SshKeyGenerationService sshKeyGenerationService;
    @Mock
    private ProjectService projectService;
    @Mock
    private com.letsblog.project.service.LetsblogSyncService letsblogSyncService;

    private SiteController controller() {
        return new SiteController(
                siteService, adminAuthorizationService, wordPressSiteProvisioningService, sshKeyGenerationService,
                projectService, letsblogSyncService);
    }

    private SiteResponse buildResponse() {
        return new SiteResponse(1L, "Name", "my-site", CmsType.WORDPRESS, "https://example.com",
                Instant.now(), Instant.now(), "SUCCESS", false, false, null);
    }

    @Test
    void register_登録できる() {
        SiteRegisterRequest request = new SiteRegisterRequest("Name", "my-site", CmsType.WORDPRESS,
                Map.of("baseUrl", "https://example.com"), null);
        when(siteService.register(request)).thenReturn(buildResponse());

        ResponseEntity<SiteResponse> response = controller().register(request);

        assertEquals(201, response.getStatusCode().value());
    }

    @Test
    void getDetail_admin権限がなければForbidden() {
        doThrow(new ForbiddenException("admin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller().getDetail(1L));
    }

    @Test
    void delete_admin権限があれば削除できる() {
        ResponseEntity<Void> response = controller().delete(1L);

        assertEquals(204, response.getStatusCode().value());
        verify(wordPressSiteProvisioningService).deleteSite(1L);
    }

    @Test
    void testConnection_結果をmapで返す() {
        when(siteService.checkConnection(1L)).thenReturn(new SiteConnectionCheckResult(true, true, null, "detail"));

        Map<String, Object> response = controller().testConnection(1L);

        assertEquals("SUCCESS", response.get("connectionCheckStatus"));
        assertEquals(true, response.get("hasAdminCapability"));
    }

    @Test
    void reprovision_admin権限があれば実行できる() {
        when(siteService.reprovision(1L)).thenReturn(
                new ProvisioningService.Result("cat-1", null, "tag-1", null, "author-1", null));

        ResponseEntity<Map<String, String>> response = controller().reprovision(1L);

        assertEquals(200, response.getStatusCode().value());
        verify(adminAuthorizationService).requireAdmin();
    }

    // ---- issue #830: 「認証済みなら誰でも」だった4件をadmin限定にした回帰テスト ----

    @Test
    void register_admin以外は拒否しサイトを登録しない() {
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class,
                () -> controller().register(new SiteRegisterRequest(
                        "Name", "my-site", CmsType.WORDPRESS, Map.of("baseUrl", "https://example.com"), null)));

        verifyNoInteractions(siteService);
    }

    @Test
    void createManagedWordPress_admin以外は拒否しプロビジョニングしない() {
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class,
                () -> controller().createManagedWordPress(null));

        verifyNoInteractions(wordPressSiteProvisioningService);
    }

    @Test
    void adoptManagedWordPress_admin以外は拒否し取り込まない() {
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class,
                () -> controller().adoptManagedWordPress(null));

        verifyNoInteractions(wordPressSiteProvisioningService);
    }

    @Test
    void testConnection_admin以外は拒否し接続確認を行わない() {
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller().testConnection(1L));

        verify(siteService, never()).checkConnection(1L);
    }

    // ---- issue #830: 一覧は所属プロジェクトに紐付くサイトだけ ----

    @Test
    void list_adminは全件を返す() {
        when(adminAuthorizationService.accessibleProjectIds()).thenReturn(Optional.empty());
        when(siteService.list(null, null)).thenReturn(List.of(buildResponse()));

        assertEquals(1, controller().list(null, null).size());
    }

    @Test
    void list_非adminは所属プロジェクトのサイトだけに絞られる() {
        // buildResponse() の id は 1。所属プロジェクトのサイトが {9} なら 1 は落ちる。
        when(adminAuthorizationService.accessibleProjectIds()).thenReturn(Optional.of(Set.of(3L)));
        when(projectService.siteIdsOfProjects(Set.of(3L))).thenReturn(Set.of(9L));
        when(siteService.list(null, null)).thenReturn(List.of(buildResponse()));

        assertEquals(List.of(), controller().list(null, null));
    }

    @Test
    void list_所属プロジェクトのサイトなら残る() {
        when(adminAuthorizationService.accessibleProjectIds()).thenReturn(Optional.of(Set.of(3L)));
        when(projectService.siteIdsOfProjects(Set.of(3L))).thenReturn(Set.of(1L));
        when(siteService.list(null, null)).thenReturn(List.of(buildResponse()));

        assertEquals(1, controller().list(null, null).size());
    }

    /** issue #1196: 実SiteService + GlobalExceptionHandler越しに、空のnameがHTTP 400で拒否されることを確認する。 */
    private MockMvc mockMvcWithRealService(SiteRepository siteRepository) {
        SiteService realService = new SiteService(siteRepository,
                new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32])), new ObjectMapper(),
                null, null, null, null);
        SiteController real = new SiteController(realService, adminAuthorizationService,
                wordPressSiteProvisioningService, sshKeyGenerationService, projectService, letsblogSyncService);
        return MockMvcBuilders.standaloneSetup(real).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void update_空文字のnameは400で原因メッセージを返す() throws Exception {
        SiteRepository siteRepository = mock(SiteRepository.class);
        Site site = new Site();
        site.setId(1L);
        site.setName("元の名前");
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));

        mockMvcWithRealService(siteRepository)
                .perform(put("/api/sites/1").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("サイト名は空にできません")));
    }

    @Test
    void update_有効なnameなら200で更新される() throws Exception {
        SiteRepository siteRepository = mock(SiteRepository.class);
        Site site = new Site();
        site.setId(1L);
        site.setName("元の名前");
        site.setCmsType(CmsType.WORDPRESS);
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));
        when(siteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        mockMvcWithRealService(siteRepository)
                .perform(put("/api/sites/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新しい名前\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("新しい名前"));
    }
    @Test
    void update_adminPathを送ると200で設定され応答に含まれる() throws Exception {
        SiteRepository siteRepository = mock(SiteRepository.class);
        Site site = new Site();
        site.setId(1L);
        site.setName("元の名前");
        site.setCmsType(CmsType.WORDPRESS);
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));
        when(siteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        mockMvcWithRealService(siteRepository)
                .perform(put("/api/sites/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adminPath\":\"secret-login\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adminPath").value("secret-login"));
    }

    @Test
    void update_不正なadminPathは400で保存済みの値は変わらない() throws Exception {
        SiteRepository siteRepository = mock(SiteRepository.class);
        Site site = new Site();
        site.setId(1L);
        site.setName("元の名前");
        site.setAdminPath("keep-me");
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));

        mockMvcWithRealService(siteRepository)
                .perform(put("/api/sites/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adminPath\":\"javascript:alert(1)\"}"))
                .andExpect(status().isBadRequest());

        assertEquals("keep-me", site.getAdminPath());
        verify(siteRepository, never()).save(any());
    }

    // ---- issue #1557: letsblogプラグインの導入状態 ----

    @Test
    void letsblogPluginStatus_admin権限で導入状態を返す() {
        com.letsblog.project.cms.LetsblogPluginStatus status = new com.letsblog.project.cms.LetsblogPluginStatus(
                com.letsblog.project.cms.LetsblogPluginStatus.State.NOT_INSTALLED, null, null);
        when(siteService.getLetsblogPluginStatus(1L)).thenReturn(status);

        assertEquals(status, controller().letsblogPluginStatus(1L));
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void letsblogPluginStatus_admin以外は拒否する() {
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller().letsblogPluginStatus(1L));
        verifyNoInteractions(siteService);
    }

    @Test
    void installLetsblogPlugin_admin権限で再導入し導入後の状態を返す() {
        com.letsblog.project.cms.LetsblogPluginStatus status = new com.letsblog.project.cms.LetsblogPluginStatus(
                com.letsblog.project.cms.LetsblogPluginStatus.State.INSTALLED, "1.0.0", 1);
        when(siteService.installLetsblogPlugin(1L)).thenReturn(status);

        assertEquals(status, controller().installLetsblogPlugin(1L));
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void installLetsblogPlugin_admin以外は拒否し導入しない() {
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller().installLetsblogPlugin(1L));
        verifyNoInteractions(siteService);
    }

    // ---- issue #1558: letsblogプラグインへの同期状態と再同期 ----

    private com.letsblog.project.dto.LetsblogSyncState failedState() {
        return new com.letsblog.project.dto.LetsblogSyncState(
                com.letsblog.project.domain.LetsblogSyncStatus.FAILED, "接続失敗", null, Instant.now());
    }

    @Test
    void letsblogSync_admin権限で同期状態を返す() {
        when(letsblogSyncService.getState(1L)).thenReturn(failedState());

        assertEquals(failedState().status(), controller().letsblogSync(1L).status());
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void resyncLetsblog_admin権限で再同期し結果の状態を返す() {
        com.letsblog.project.dto.LetsblogSyncState synced = new com.letsblog.project.dto.LetsblogSyncState(
                com.letsblog.project.domain.LetsblogSyncStatus.SYNCED, null, "h1", Instant.now());
        when(letsblogSyncService.syncSiteNow(1L)).thenReturn(synced);

        assertEquals(synced, controller().resyncLetsblog(1L));
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void resyncLetsblog_admin以外は拒否する() {
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller().resyncLetsblog(1L));
        verifyNoInteractions(letsblogSyncService);
    }
}
