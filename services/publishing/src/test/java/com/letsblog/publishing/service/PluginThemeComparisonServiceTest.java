package com.letsblog.publishing.service;

import com.letsblog.publishing.cms.CmsType;
import com.letsblog.publishing.domain.BulkOperationLog;
import com.letsblog.publishing.domain.BulkOperationType;
import com.letsblog.publishing.domain.Project;
import com.letsblog.publishing.domain.Site;
import com.letsblog.publishing.dto.ReconcileStateRequest.StateChangeRequest;
import com.letsblog.publishing.dto.StatusComparisonPage;
import com.letsblog.publishing.provisioning.WordPressBulkManagementClient;
import com.letsblog.publishing.provisioning.WordPressBulkManagementClient.PluginThemeInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PluginThemeComparisonServiceTest {

    @Mock
    private WordPressBulkManagementClient bulkManagementClient;

    @Mock
    private BulkManagementService bulkManagementService;

    @Mock
    private SiteService siteService;

    @Mock
    private ProjectService projectService;

    @Mock
    private com.letsblog.publishing.cms.ssh.WordPressSshOperations sshOperations;

    private PluginThemeComparisonService service() {
        return new PluginThemeComparisonService(
                bulkManagementClient, bulkManagementService, siteService, projectService,
                sshOperations);
    }

    private Project buildProject(Long localSiteId, Long testSiteId, Long productionSiteId, String masterEnvironment) {
        Project project = new Project();
        project.setId(1L);
        project.setName("テスト");
        project.setSlug("test");
        project.setLocalSiteId(localSiteId);
        project.setTestSiteId(testSiteId);
        project.setProductionSiteId(productionSiteId);
        project.setMasterEnvironment(masterEnvironment);
        return project;
    }

    private Site buildManagedSite(Long id, String slug) {
        Site site = new Site();
        site.setId(id);
        site.setSiteKey(slug);
        site.setCmsType(CmsType.WORDPRESS);
        site.setManagedWordpress(true);
        site.setWpSlug(slug);
        return site;
    }

    @Test
    void listPluginComparison_未紐付け環境はavailable_falseになり_未インストールはNOT_INSTALLEDになる() {
        PluginThemeComparisonService service = service();
        Project project = buildProject(10L, 20L, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
        when(bulkManagementClient.listPlugins("local-site")).thenReturn(List.of());
        when(bulkManagementClient.listPlugins("test-site"))
                .thenReturn(List.of(new PluginThemeInfo("akismet", "active")));

        StatusComparisonPage page = service.listPluginComparison(1L, 0, 20);

        assertEquals(1, page.items().size());
        assertEquals("akismet", page.items().get(0).slug());
        assertEquals("test", page.masterEnvironment());
        assertTrue(page.items().get(0).local().available());
        assertEquals("NOT_INSTALLED", page.items().get(0).local().status());
        assertEquals("ACTIVE", page.items().get(0).test().status());
        assertFalse(page.items().get(0).production().available());
    }

    @Test
    void reconcilePlugin_未インストールから有効へはインストールと有効化を実行する() {
        PluginThemeComparisonService service = service();
        Project project = buildProject(10L, null, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.listPlugins("local-site")).thenReturn(List.of());
        when(bulkManagementService.applyToEnvironment(eq(1L), any(), any(), any(), any(), any(), any(), any(), eq(9L)))
                .thenReturn(new BulkOperationLog());

        service.reconcilePlugin(1L, "akismet", List.of(new StateChangeRequest("local", "ACTIVE")), 9L);

        verify(bulkManagementService).applyToEnvironment(
                1L, "local", BulkOperationType.PLUGIN_INSTALL, "akismet", null, null, null, null, 9L);
        verify(bulkManagementService).applyToEnvironment(
                1L, "local", BulkOperationType.PLUGIN_ACTIVATE, "akismet", null, null, null, null, 9L);
    }

    @Test
    void reconcilePlugin_現在状態と希望状態が同じなら何も実行しない() {
        PluginThemeComparisonService service = service();
        Project project = buildProject(10L, null, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.listPlugins("local-site"))
                .thenReturn(List.of(new PluginThemeInfo("akismet", "active")));

        List<BulkOperationLog> results = service.reconcilePlugin(
                1L, "akismet", List.of(new StateChangeRequest("local", "ACTIVE")), 9L);

        assertTrue(results.isEmpty());
        verify(bulkManagementService, never()).applyToEnvironment(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void reconcileTheme_有効から無効への遷移は例外_テーマは間接的にのみ切替可能() {
        PluginThemeComparisonService service = service();
        Project project = buildProject(10L, null, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.listThemes("local-site"))
                .thenReturn(List.of(new PluginThemeInfo("twentytwentyfour", "active")));

        assertThrows(IllegalArgumentException.class, () -> service.reconcileTheme(
                1L, "twentytwentyfour", List.of(new StateChangeRequest("local", "INACTIVE")), 9L));
    }

    @Test
    void deletePluginEverywhere_インストール済みの環境のみ削除される() {
        PluginThemeComparisonService service = service();
        Project project = buildProject(10L, 20L, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
        when(bulkManagementClient.listPlugins("local-site")).thenReturn(List.of());
        when(bulkManagementClient.listPlugins("test-site"))
                .thenReturn(List.of(new PluginThemeInfo("akismet", "inactive")));
        when(bulkManagementService.applyToEnvironment(eq(1L), any(), any(), any(), any(), any(), any(), any(), eq(9L)))
                .thenReturn(new BulkOperationLog());

        List<BulkOperationLog> results = service.deletePluginEverywhere(1L, "akismet", 9L);

        assertEquals(1, results.size());
        verify(bulkManagementService).applyToEnvironment(
                1L, "test", BulkOperationType.PLUGIN_DELETE, "akismet", null, null, null, null, 9L);
        verify(bulkManagementService, never()).applyToEnvironment(
                eq(1L), eq("local"), any(), any(), any(), any(), any(), any(), eq(9L));
    }

    @Test
    void deletePluginEverywhere_どの環境にもインストールされていなければ例外() {
        PluginThemeComparisonService service = service();
        Project project = buildProject(10L, null, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.listPlugins("local-site")).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class, () -> service.deletePluginEverywhere(1L, "akismet", 9L));
    }

    // ---- 非managedサイト: SSHホスト単位まとめ取得・エラー ----

    private Site buildExternalSite(Long id, String slug) {
        Site site = new Site();
        site.setId(id);
        site.setSiteKey(slug);
        site.setCmsType(CmsType.WORDPRESS);
        site.setManagedWordpress(false);
        return site;
    }

    private com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials sshCreds(String wpPath) {
        return new com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials(
                "https://example.com", null, "SSH", "203.0.113.5", 22, "deploy", wpPath, "PEM", null, null);
    }

    @Test
    void listPluginComparison_同一ホストのSSH環境は1回のfetchPluginsOrThemesForEnvironmentsにまとめる() {
        PluginThemeComparisonService service = service();
        Project project = buildProject(null, 20L, 30L, "test");
        Site testSite = buildExternalSite(20L, "test-site");
        Site productionSite = buildExternalSite(30L, "production-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
        when(siteService.getById(30L)).thenReturn(Optional.of(productionSite));
        when(siteService.resolveDataSource(testSite))
                .thenReturn(new SiteService.SiteDataSource(false, sshCreds("/var/www/html/test")));
        when(siteService.resolveDataSource(productionSite))
                .thenReturn(new SiteService.SiteDataSource(false, sshCreds("/var/www/html/production")));
        when(sshOperations.fetchPluginsOrThemesForEnvironments(eq("plugin"), any())).thenReturn(
                new com.letsblog.publishing.cms.ssh.WordPressSshOperations.EnvironmentFetchResult<>(
                        java.util.Map.of(
                                "test", List.of(new com.letsblog.publishing.cms.ssh.WordPressSshOperations.PluginThemeInfo(
                                        "akismet", "active")),
                                "production", List.of(new com.letsblog.publishing.cms.ssh.WordPressSshOperations.PluginThemeInfo(
                                        "akismet", "inactive"))),
                        java.util.Map.of(), java.util.Map.of()));

        StatusComparisonPage page = service.listPluginComparison(1L, 0, 20);

        assertEquals(1, page.items().size());
        assertEquals("ACTIVE", page.items().get(0).test().status());
        assertEquals("INACTIVE", page.items().get(0).production().status());
        verify(sshOperations, org.mockito.Mockito.times(1))
                .fetchPluginsOrThemesForEnvironments(eq("plugin"), any());
    }

    /** issue #1137レビュー対応: toValue()のerror()分岐(L141)のカバレッジを補う。 */
    @Test
    void listPluginComparison_一部環境がエラーでも他環境のslugはerror値として表示される() {
        PluginThemeComparisonService service = service();
        Project project = buildProject(10L, 20L, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildExternalSite(20L, "test-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
        when(bulkManagementClient.listPlugins("local-site"))
                .thenReturn(List.of(new PluginThemeInfo("akismet", "active")));
        when(siteService.resolveDataSource(testSite))
                .thenReturn(new SiteService.SiteDataSource(false, sshCreds("/var/www/html/test")));
        when(sshOperations.fetchPluginsOrThemesForEnvironments(eq("plugin"), any())).thenReturn(
                new com.letsblog.publishing.cms.ssh.WordPressSshOperations.EnvironmentFetchResult<>(
                        java.util.Map.of(), java.util.Map.of("test", "Connection refused"),
                        java.util.Map.of("test", "stacktrace")));

        StatusComparisonPage page = service.listPluginComparison(1L, 0, 20);

        assertEquals(1, page.items().size());
        assertEquals("ACTIVE", page.items().get(0).local().status());
        assertFalse(page.items().get(0).test().available());
        assertEquals("Connection refused", page.items().get(0).test().errorMessage());
    }

    // ---- issue #1682: agent経由の一覧取得失敗はerror値になり作業ログに記録される ----

    private void bindLocalAndTestManaged() {
        Project project = buildProject(10L, 20L, null, "test");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(buildManagedSite(10L, "local-site")));
        when(siteService.getById(20L)).thenReturn(Optional.of(buildManagedSite(20L, "test-site")));
    }

    @Test
    void listPluginComparison_agentの読み取りタイムアウトはerror値になりPLUGIN_FETCHで記録する() {
        PluginThemeComparisonService service = service();
        bindLocalAndTestManaged();
        when(bulkManagementClient.listPlugins("local-site"))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("Read timed out"));
        when(bulkManagementClient.listPlugins("test-site"))
                .thenReturn(List.of(new PluginThemeInfo("akismet", "active")));

        StatusComparisonPage page = service.listPluginComparison(1L, 0, 20);

        assertEquals(1, page.items().size());
        assertFalse(page.items().get(0).local().available());
        assertEquals("Read timed out", page.items().get(0).local().errorMessage());
        assertEquals("ACTIVE", page.items().get(0).test().status());
        verify(bulkManagementService).logFetchFailure(
                eq(1L), eq(BulkOperationType.PLUGIN_FETCH), eq("local"), eq("Read timed out"), any());
    }

    @Test
    void listThemeComparison_agentの読み取りタイムアウトはerror値になりTHEME_FETCHで記録する() {
        PluginThemeComparisonService service = service();
        bindLocalAndTestManaged();
        when(bulkManagementClient.listThemes("local-site"))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("Read timed out"));
        when(bulkManagementClient.listThemes("test-site"))
                .thenReturn(List.of(new PluginThemeInfo("twentytwenty", "active")));

        StatusComparisonPage page = service.listThemeComparison(1L, 0, 20);

        assertEquals(1, page.items().size());
        assertFalse(page.items().get(0).local().available());
        assertEquals("Read timed out", page.items().get(0).local().errorMessage());
        verify(bulkManagementService).logFetchFailure(
                eq(1L), eq(BulkOperationType.THEME_FETCH), eq("local"), eq("Read timed out"), any());
    }

    @Test
    void listPluginComparison_agentが5xxを返した環境もerror値になり作業ログに記録する() {
        PluginThemeComparisonService service = service();
        bindLocalAndTestManaged();
        when(bulkManagementClient.listPlugins("local-site"))
                .thenReturn(List.of(new PluginThemeInfo("akismet", "active")));
        when(bulkManagementClient.listPlugins("test-site")).thenThrow(
                new org.springframework.web.client.HttpServerErrorException(
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR));

        StatusComparisonPage page = service.listPluginComparison(1L, 0, 20);

        assertEquals(1, page.items().size());
        assertFalse(page.items().get(0).test().available());
        assertTrue(page.items().get(0).test().errorMessage().contains("500"));
        verify(bulkManagementService).logFetchFailure(
                eq(1L), eq(BulkOperationType.PLUGIN_FETCH), eq("test"), any(), any());
    }

    @Test
    void listPluginComparison_agentが4xxを返した環境もerror値になる() {
        PluginThemeComparisonService service = service();
        bindLocalAndTestManaged();
        when(bulkManagementClient.listPlugins("local-site")).thenThrow(
                new org.springframework.web.client.HttpClientErrorException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED));
        when(bulkManagementClient.listPlugins("test-site"))
                .thenReturn(List.of(new PluginThemeInfo("akismet", "active")));

        StatusComparisonPage page = service.listPluginComparison(1L, 0, 20);

        assertFalse(page.items().get(0).local().available());
        verify(bulkManagementService).logFetchFailure(
                eq(1L), eq(BulkOperationType.PLUGIN_FETCH), eq("local"), any(), any());
    }

    @Test
    void listPluginComparison_agentがその他のRestClientExceptionを投げた環境もerror値になる() {
        PluginThemeComparisonService service = service();
        bindLocalAndTestManaged();
        when(bulkManagementClient.listPlugins("local-site"))
                .thenThrow(new org.springframework.web.client.RestClientException("unexpected body"));
        when(bulkManagementClient.listPlugins("test-site"))
                .thenReturn(List.of(new PluginThemeInfo("akismet", "active")));

        StatusComparisonPage page = service.listPluginComparison(1L, 0, 20);

        assertFalse(page.items().get(0).local().available());
        assertEquals("unexpected body", page.items().get(0).local().errorMessage());
    }

    @Test
    void listPluginComparison_agentが正常に0件を返した環境はエラーにならず未インストールになる() {
        PluginThemeComparisonService service = service();
        bindLocalAndTestManaged();
        when(bulkManagementClient.listPlugins("local-site")).thenReturn(List.of());
        when(bulkManagementClient.listPlugins("test-site"))
                .thenReturn(List.of(new PluginThemeInfo("akismet", "active")));

        StatusComparisonPage page = service.listPluginComparison(1L, 0, 20);

        assertTrue(page.items().get(0).local().available());
        assertEquals("NOT_INSTALLED", page.items().get(0).local().status());
        verify(bulkManagementService, org.mockito.Mockito.never())
                .logFetchFailure(any(), any(), any(), any(), any());
    }

    // ---- reconcile: byEnvironment側の対象外分岐(L162) ----

    /** issue #1137レビュー対応: 対応する環境自体が存在しない(envInfos==null)分岐のカバレッジを補う。 */
    @Test
    void reconcilePlugin_未知の環境名を指定すると例外() {
        PluginThemeComparisonService service = service();
        Project project = buildProject(10L, null, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.listPlugins("local-site")).thenReturn(List.of());

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> service.reconcilePlugin(
                1L, "akismet", List.of(new StateChangeRequest("staging", "ACTIVE")), 9L));
        assertTrue(thrown.getMessage().contains("staging"));
    }

    /** issue #1137レビュー対応: 環境は存在するが対象外(infos()==null)分岐のカバレッジを補う。 */
    @Test
    void reconcilePlugin_サイト未紐付けの環境を指定すると例外() {
        PluginThemeComparisonService service = service();
        Project project = buildProject(10L, null, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.listPlugins("local-site")).thenReturn(List.of());

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> service.reconcilePlugin(
                1L, "akismet", List.of(new StateChangeRequest("test", "ACTIVE")), 9L));
        assertTrue(thrown.getMessage().contains("test"));
    }

    // ---- stepsFor: 状態遷移の分岐網羅(L184/L185/L189/L190/L192/L195) ----

    @Test
    void reconcileTheme_未インストールから有効へはテーマのインストールと有効化を実行する() {
        PluginThemeComparisonService service = service();
        Project project = buildProject(10L, null, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.listThemes("local-site")).thenReturn(List.of());
        when(bulkManagementService.applyToEnvironment(eq(1L), any(), any(), any(), any(), any(), any(), any(), eq(9L)))
                .thenReturn(new BulkOperationLog());

        service.reconcileTheme(1L, "twentytwentyfour", List.of(new StateChangeRequest("local", "ACTIVE")), 9L);

        verify(bulkManagementService).applyToEnvironment(
                1L, "local", BulkOperationType.THEME_INSTALL, "twentytwentyfour", null, null, null, null, 9L);
        verify(bulkManagementService).applyToEnvironment(
                1L, "local", BulkOperationType.THEME_ACTIVATE, "twentytwentyfour", null, null, null, null, 9L);
    }

    @Test
    void reconcilePlugin_未インストールから無効へはインストールのみ実行する() {
        PluginThemeComparisonService service = service();
        Project project = buildProject(10L, null, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.listPlugins("local-site")).thenReturn(List.of());
        when(bulkManagementService.applyToEnvironment(eq(1L), any(), any(), any(), any(), any(), any(), any(), eq(9L)))
                .thenReturn(new BulkOperationLog());

        List<BulkOperationLog> results = service.reconcilePlugin(
                1L, "akismet", List.of(new StateChangeRequest("local", "INACTIVE")), 9L);

        assertEquals(1, results.size());
        verify(bulkManagementService).applyToEnvironment(
                1L, "local", BulkOperationType.PLUGIN_INSTALL, "akismet", null, null, null, null, 9L);
        verify(bulkManagementService, never()).applyToEnvironment(
                eq(1L), eq("local"), eq(BulkOperationType.PLUGIN_ACTIVATE), any(), any(), any(), any(), any(), any());
    }

    @Test
    void reconcileTheme_未インストールから無効へはテーマのインストールのみ実行する() {
        PluginThemeComparisonService service = service();
        Project project = buildProject(10L, null, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.listThemes("local-site")).thenReturn(List.of());
        when(bulkManagementService.applyToEnvironment(eq(1L), any(), any(), any(), any(), any(), any(), any(), eq(9L)))
                .thenReturn(new BulkOperationLog());

        List<BulkOperationLog> results = service.reconcileTheme(
                1L, "twentytwentyfour", List.of(new StateChangeRequest("local", "INACTIVE")), 9L);

        assertEquals(1, results.size());
        verify(bulkManagementService).applyToEnvironment(
                1L, "local", BulkOperationType.THEME_INSTALL, "twentytwentyfour", null, null, null, null, 9L);
    }

    @Test
    void reconcilePlugin_無効から有効へは有効化のみ実行する() {
        PluginThemeComparisonService service = service();
        Project project = buildProject(10L, null, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.listPlugins("local-site"))
                .thenReturn(List.of(new PluginThemeInfo("akismet", "inactive")));
        when(bulkManagementService.applyToEnvironment(eq(1L), any(), any(), any(), any(), any(), any(), any(), eq(9L)))
                .thenReturn(new BulkOperationLog());

        List<BulkOperationLog> results = service.reconcilePlugin(
                1L, "akismet", List.of(new StateChangeRequest("local", "ACTIVE")), 9L);

        assertEquals(1, results.size());
        verify(bulkManagementService).applyToEnvironment(
                1L, "local", BulkOperationType.PLUGIN_ACTIVATE, "akismet", null, null, null, null, 9L);
    }

    @Test
    void reconcileTheme_無効から有効へはテーマの有効化のみ実行する() {
        PluginThemeComparisonService service = service();
        Project project = buildProject(10L, null, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.listThemes("local-site"))
                .thenReturn(List.of(new PluginThemeInfo("twentytwentyfour", "inactive")));
        when(bulkManagementService.applyToEnvironment(eq(1L), any(), any(), any(), any(), any(), any(), any(), eq(9L)))
                .thenReturn(new BulkOperationLog());

        List<BulkOperationLog> results = service.reconcileTheme(
                1L, "twentytwentyfour", List.of(new StateChangeRequest("local", "ACTIVE")), 9L);

        assertEquals(1, results.size());
        verify(bulkManagementService).applyToEnvironment(
                1L, "local", BulkOperationType.THEME_ACTIVATE, "twentytwentyfour", null, null, null, null, 9L);
    }

    /** issue #1137レビュー対応: 無効(INACTIVE)から未インストールへの遷移は未サポート(L192の残余分岐)。 */
    @Test
    void reconcilePlugin_無効から未インストールへの遷移は例外() {
        PluginThemeComparisonService service = service();
        Project project = buildProject(10L, null, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.listPlugins("local-site"))
                .thenReturn(List.of(new PluginThemeInfo("akismet", "inactive")));

        assertThrows(IllegalArgumentException.class, () -> service.reconcilePlugin(
                1L, "akismet", List.of(new StateChangeRequest("local", "NOT_INSTALLED")), 9L));
    }

    /** issue #1137レビュー対応: 有効から未インストールへの遷移は未サポート(L195のdesired!=INACTIVE分岐)。 */
    @Test
    void reconcilePlugin_有効から未インストールへの遷移は例外() {
        PluginThemeComparisonService service = service();
        Project project = buildProject(10L, null, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.listPlugins("local-site"))
                .thenReturn(List.of(new PluginThemeInfo("akismet", "active")));

        assertThrows(IllegalArgumentException.class, () -> service.reconcilePlugin(
                1L, "akismet", List.of(new StateChangeRequest("local", "NOT_INSTALLED")), 9L));
    }

    /** issue #1137レビュー対応: 有効から無効へのプラグイン無効化(L195が真になる唯一の成功経路)。 */
    @Test
    void reconcilePlugin_有効から無効へは無効化のみ実行する() {
        PluginThemeComparisonService service = service();
        Project project = buildProject(10L, null, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.listPlugins("local-site"))
                .thenReturn(List.of(new PluginThemeInfo("akismet", "active")));
        when(bulkManagementService.applyToEnvironment(eq(1L), any(), any(), any(), any(), any(), any(), any(), eq(9L)))
                .thenReturn(new BulkOperationLog());

        List<BulkOperationLog> results = service.reconcilePlugin(
                1L, "akismet", List.of(new StateChangeRequest("local", "INACTIVE")), 9L);

        assertEquals(1, results.size());
        verify(bulkManagementService).applyToEnvironment(
                1L, "local", BulkOperationType.PLUGIN_DEACTIVATE, "akismet", null, null, null, null, 9L);
    }

    /**
     * issue #1137レビュー対応: 未インストール状態から{@code desired}がINACTIVEでもACTIVEでもない
     * (未サポートな)希望状態を指定した場合の残余分岐(L189のdesired.equals(INACTIVE)=false)。
     */
    @Test
    void reconcilePlugin_未インストールから未サポートな希望状態への遷移は例外() {
        PluginThemeComparisonService service = service();
        Project project = buildProject(10L, null, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.listPlugins("local-site")).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class, () -> service.reconcilePlugin(
                1L, "akismet", List.of(new StateChangeRequest("local", "BOGUS")), 9L));
    }

    // ---- deleteEverywhere: isTheme分岐(L204) ----

    /** issue #1137レビュー対応: deleteThemeEverywhereでのdeleteType三項演算子(L204)のカバレッジを補う。 */
    @Test
    void deleteThemeEverywhere_インストール済みの環境のみ削除される() {
        PluginThemeComparisonService service = service();
        Project project = buildProject(10L, null, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.listThemes("local-site"))
                .thenReturn(List.of(new PluginThemeInfo("twentytwentyfour", "inactive")));
        when(bulkManagementService.applyToEnvironment(eq(1L), any(), any(), any(), any(), any(), any(), any(), eq(9L)))
                .thenReturn(new BulkOperationLog());

        List<BulkOperationLog> results = service.deleteThemeEverywhere(1L, "twentytwentyfour", 9L);

        assertEquals(1, results.size());
        verify(bulkManagementService).applyToEnvironment(
                1L, "local", BulkOperationType.THEME_DELETE, "twentytwentyfour", null, null, null, null, 9L);
    }

    // ---- resolveInfosByEnvironment: SSH非対応(L246)/テーマ側SSH(L257,L291,L293) ----

    /** issue #1137レビュー対応: 非managedかつSSHも使えないサイトはunavailableになる分岐(L246)のカバレッジを補う。 */
    @Test
    void listPluginComparison_SSHも使えない非managedサイトはunavailableになる() {
        PluginThemeComparisonService service = service();
        Project project = buildProject(10L, null, null, "test");
        Site externalSite = buildExternalSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(externalSite));
        when(siteService.resolveDataSource(externalSite))
                .thenReturn(new SiteService.SiteDataSource(false, null));

        StatusComparisonPage page = service.listPluginComparison(1L, 0, 20);

        assertEquals(0, page.items().size());
        verify(sshOperations, never()).fetchPluginsOrThemesForEnvironments(any(), any());
    }

    /** issue #1137レビュー対応: テーマ一覧のSSH取得(L257のisTheme=true側/L291/L293)のカバレッジを補う。 */
    @Test
    void listThemeComparison_SSH取得失敗時はerror値になり作業ログに記録する() {
        PluginThemeComparisonService service = service();
        Project project = buildProject(null, 20L, null, "test");
        Site testSite = buildExternalSite(20L, "test-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
        when(siteService.resolveDataSource(testSite))
                .thenReturn(new SiteService.SiteDataSource(false, sshCreds("/var/www/html/test")));
        when(sshOperations.fetchPluginsOrThemesForEnvironments(eq("theme"), any())).thenReturn(
                new com.letsblog.publishing.cms.ssh.WordPressSshOperations.EnvironmentFetchResult<>(
                        java.util.Map.of(), java.util.Map.of("test", "Connection refused"),
                        java.util.Map.of("test", "stacktrace")));

        StatusComparisonPage page = service.listThemeComparison(1L, 0, 20);

        assertEquals(0, page.items().size());
        verify(sshOperations).fetchPluginsOrThemesForEnvironments(eq("theme"), any());
        verify(bulkManagementService).logFetchFailure(
                eq(1L), eq(BulkOperationType.THEME_FETCH), eq("test"), eq("Connection refused"), eq("stacktrace"));
    }

    /** issue #1137レビュー対応: sshPort未設定時は既定の22番ポートでホストキーをまとめる分岐(L285)のカバレッジを補う。 */
    @Test
    void listPluginComparison_sshPort未設定なら既定の22番でホストをまとめる() {
        PluginThemeComparisonService service = service();
        Project project = buildProject(null, 20L, 30L, "test");
        Site testSite = buildExternalSite(20L, "test-site");
        Site productionSite = buildExternalSite(30L, "production-site");
        com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials credsWithoutPort =
                new com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials(
                        "https://example.com", null, "SSH", "203.0.113.5", null, "deploy",
                        "/var/www/html/test", "PEM", null, null);
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
        when(siteService.getById(30L)).thenReturn(Optional.of(productionSite));
        when(siteService.resolveDataSource(testSite))
                .thenReturn(new SiteService.SiteDataSource(false, credsWithoutPort));
        when(siteService.resolveDataSource(productionSite))
                .thenReturn(new SiteService.SiteDataSource(false, sshCreds("/var/www/html/production")));
        when(sshOperations.fetchPluginsOrThemesForEnvironments(eq("plugin"), any())).thenReturn(
                new com.letsblog.publishing.cms.ssh.WordPressSshOperations.EnvironmentFetchResult<>(
                        java.util.Map.of(), java.util.Map.of(), java.util.Map.of()));

        service.listPluginComparison(1L, 0, 20);

        // 双方とも同一ホスト(203.0.113.5)かつポート未設定=既定22番なので、production側の
        // 明示的な22番指定と同一ホストキーになり1回のfetchにまとまる。
        verify(sshOperations, org.mockito.Mockito.times(1))
                .fetchPluginsOrThemesForEnvironments(eq("plugin"), any());
    }

    @Test
    void listPluginComparison_SSH取得失敗時はerror値になり作業ログに記録する() {
        PluginThemeComparisonService service = service();
        Project project = buildProject(null, 20L, null, "test");
        Site testSite = buildExternalSite(20L, "test-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
        when(siteService.resolveDataSource(testSite))
                .thenReturn(new SiteService.SiteDataSource(false, sshCreds("/var/www/html/test")));
        when(sshOperations.fetchPluginsOrThemesForEnvironments(eq("plugin"), any())).thenReturn(
                new com.letsblog.publishing.cms.ssh.WordPressSshOperations.EnvironmentFetchResult<>(
                        java.util.Map.of(), java.util.Map.of("test", "Connection refused"),
                        java.util.Map.of("test", "java.io.IOException: Connection refused\n\tat ...")));

        StatusComparisonPage page = service.listPluginComparison(1L, 0, 20);

        assertEquals(0, page.items().size());
        verify(bulkManagementService).logFetchFailure(
                eq(1L), eq(BulkOperationType.PLUGIN_FETCH), eq("test"), eq("Connection refused"),
                eq("java.io.IOException: Connection refused\n\tat ..."));
    }

    // ---- issue #1687: 環境間の並列実行 ----

    private void bindThreeManagedSites() {
        Project project = buildProject(10L, 20L, 30L, "test");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(buildManagedSite(10L, "local-site")));
        when(siteService.getById(20L)).thenReturn(Optional.of(buildManagedSite(20L, "test-site")));
        when(siteService.getById(30L)).thenReturn(Optional.of(buildManagedSite(30L, "production-site")));
    }

    private BulkOperationLog logOf(String environment, BulkOperationType type) {
        BulkOperationLog log = new BulkOperationLog();
        log.setEnvironment(environment);
        log.setOperationType(type);
        return log;
    }

    @Test
    void 一覧取得_managed環境ごとのagent取得が重なって走る() {
        PluginThemeComparisonService service = service();
        bindThreeManagedSites();
        OverlapProbe probe = new OverlapProbe(3);
        when(bulkManagementClient.listPlugins(any())).thenAnswer(invocation -> {
            probe.enter();
            return List.of(new PluginThemeInfo("akismet", "active"));
        });

        StatusComparisonPage page = service.listPluginComparison(1L, 0, 20);

        assertTrue(probe.allOverlapped(), "3環境の一覧取得が重なって呼ばれていない(逐次実行)");
        assertEquals(1, page.items().size());
    }

    @Test
    void 一覧取得_agent取得の実行時例外は並列でも呼び出し元へそのまま伝わる() {
        PluginThemeComparisonService service = service();
        bindThreeManagedSites();
        when(bulkManagementClient.listPlugins("local-site")).thenThrow(new IllegalStateException("agent down"));
        when(bulkManagementClient.listPlugins("test-site")).thenReturn(List.of());
        when(bulkManagementClient.listPlugins("production-site")).thenReturn(List.of());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.listPluginComparison(1L, 0, 20));

        assertEquals("agent down", thrown.getMessage());
    }

    @Test
    void deletePluginEverywhere_環境ごとの削除が重なって走り_結果はENVIRONMENT_ORDER順() {
        PluginThemeComparisonService service = service();
        bindThreeManagedSites();
        when(bulkManagementClient.listPlugins(any()))
                .thenReturn(List.of(new PluginThemeInfo("akismet", "inactive")));
        OverlapProbe probe = new OverlapProbe(3);
        when(bulkManagementService.applyToEnvironment(
                eq(1L), any(), eq(BulkOperationType.PLUGIN_DELETE), eq("akismet"), any(), any(), any(), any(), eq(9L)))
                .thenAnswer(invocation -> {
                    String environment = invocation.getArgument(1);
                    if (environment.equals("local")) {
                        Thread.sleep(150);
                    }
                    probe.enter();
                    return logOf(environment, BulkOperationType.PLUGIN_DELETE);
                });

        List<BulkOperationLog> results = service.deletePluginEverywhere(1L, "akismet", 9L);

        assertTrue(probe.allOverlapped(), "3環境の削除が重なって呼ばれていない(逐次実行)");
        assertEquals(List.of("local", "test", "production"),
                results.stream().map(BulkOperationLog::getEnvironment).toList());
    }

    @Test
    void deleteThemeEverywhere_環境ごとの削除が重なって走り_結果はENVIRONMENT_ORDER順() {
        PluginThemeComparisonService service = service();
        bindThreeManagedSites();
        when(bulkManagementClient.listThemes(any()))
                .thenReturn(List.of(new PluginThemeInfo("twentytwentyfour", "inactive")));
        OverlapProbe probe = new OverlapProbe(3);
        when(bulkManagementService.applyToEnvironment(
                eq(1L), any(), eq(BulkOperationType.THEME_DELETE), eq("twentytwentyfour"),
                any(), any(), any(), any(), eq(9L)))
                .thenAnswer(invocation -> {
                    String environment = invocation.getArgument(1);
                    if (environment.equals("local")) {
                        Thread.sleep(150);
                    }
                    probe.enter();
                    return logOf(environment, BulkOperationType.THEME_DELETE);
                });

        List<BulkOperationLog> results = service.deleteThemeEverywhere(1L, "twentytwentyfour", 9L);

        assertTrue(probe.allOverlapped(), "3環境のテーマ削除が重なって呼ばれていない(逐次実行)");
        assertEquals(List.of("local", "test", "production"),
                results.stream().map(BulkOperationLog::getEnvironment).toList());
    }

    @Test
    void deletePluginEverywhere_1環境の実行時例外でも他環境の削除は最後まで実行される() {
        PluginThemeComparisonService service = service();
        bindThreeManagedSites();
        when(bulkManagementClient.listPlugins(any()))
                .thenReturn(List.of(new PluginThemeInfo("akismet", "inactive")));
        when(bulkManagementService.applyToEnvironment(
                eq(1L), any(), eq(BulkOperationType.PLUGIN_DELETE), eq("akismet"), any(), any(), any(), any(), eq(9L)))
                .thenAnswer(invocation -> {
                    String environment = invocation.getArgument(1);
                    if (environment.equals("local")) {
                        throw new IllegalStateException("delete failed");
                    }
                    Thread.sleep(100);
                    return logOf(environment, BulkOperationType.PLUGIN_DELETE);
                });

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.deletePluginEverywhere(1L, "akismet", 9L));

        assertEquals("delete failed", thrown.getMessage());
        verify(bulkManagementService).applyToEnvironment(
                1L, "test", BulkOperationType.PLUGIN_DELETE, "akismet", null, null, null, null, 9L);
        verify(bulkManagementService).applyToEnvironment(
                1L, "production", BulkOperationType.PLUGIN_DELETE, "akismet", null, null, null, null, 9L);
    }

    @Test
    void reconcilePlugin_環境間は重なって走り_結果はchangesの順_環境内の段階順は保たれる() {
        PluginThemeComparisonService service = service();
        bindThreeManagedSites();
        when(bulkManagementClient.listPlugins(any())).thenReturn(List.of());
        OverlapProbe probe = new OverlapProbe(3);
        java.util.concurrent.ConcurrentLinkedQueue<String> calls = new java.util.concurrent.ConcurrentLinkedQueue<>();
        when(bulkManagementService.applyToEnvironment(
                eq(1L), any(), any(), eq("akismet"), any(), any(), any(), any(), eq(9L)))
                .thenAnswer(invocation -> {
                    String environment = invocation.getArgument(1);
                    BulkOperationType type = invocation.getArgument(2);
                    calls.add(environment + ":" + type);
                    if (type == BulkOperationType.PLUGIN_INSTALL) {
                        probe.enter();
                    } else {
                        // INSTALLより後に走ることが環境内の段階順の証拠になる
                        Thread.sleep(50);
                    }
                    return logOf(environment, type);
                });

        // changesは local/test/production の逆順
        List<BulkOperationLog> results = service.reconcilePlugin(1L, "akismet", List.of(
                new StateChangeRequest("production", "ACTIVE"),
                new StateChangeRequest("test", "ACTIVE"),
                new StateChangeRequest("local", "ACTIVE")), 9L);

        assertTrue(probe.allOverlapped(), "3環境の反映が重なって呼ばれていない(逐次実行)");
        assertEquals(List.of(
                "production:PLUGIN_INSTALL", "production:PLUGIN_ACTIVATE",
                "test:PLUGIN_INSTALL", "test:PLUGIN_ACTIVATE",
                "local:PLUGIN_INSTALL", "local:PLUGIN_ACTIVATE"),
                results.stream().map(r -> r.getEnvironment() + ":" + r.getOperationType()).toList());
        for (String environment : List.of("local", "test", "production")) {
            List<String> perEnvironment = calls.stream().filter(c -> c.startsWith(environment + ":")).toList();
            assertEquals(List.of(environment + ":PLUGIN_INSTALL", environment + ":PLUGIN_ACTIVATE"), perEnvironment);
        }
    }

    @Test
    void reconcileTheme_環境間は重なって走る() {
        PluginThemeComparisonService service = service();
        bindThreeManagedSites();
        when(bulkManagementClient.listThemes(any())).thenReturn(List.of());
        OverlapProbe probe = new OverlapProbe(2);
        when(bulkManagementService.applyToEnvironment(
                eq(1L), any(), any(), eq("twentytwentyfour"), any(), any(), any(), any(), eq(9L)))
                .thenAnswer(invocation -> {
                    String environment = invocation.getArgument(1);
                    BulkOperationType type = invocation.getArgument(2);
                    if (type == BulkOperationType.THEME_INSTALL) {
                        probe.enter();
                    }
                    return logOf(environment, type);
                });

        List<BulkOperationLog> results = service.reconcileTheme(1L, "twentytwentyfour", List.of(
                new StateChangeRequest("test", "INACTIVE"),
                new StateChangeRequest("local", "INACTIVE")), 9L);

        assertTrue(probe.allOverlapped(), "2環境のテーマ反映が重なって呼ばれていない(逐次実行)");
        assertEquals(List.of("test", "local"), results.stream().map(BulkOperationLog::getEnvironment).toList());
    }

    @Test
    void reconcilePlugin_1環境の実行時例外でも他環境の反映は最後まで実行される() {
        PluginThemeComparisonService service = service();
        bindThreeManagedSites();
        when(bulkManagementClient.listPlugins(any())).thenReturn(List.of());
        when(bulkManagementService.applyToEnvironment(
                eq(1L), any(), any(), eq("akismet"), any(), any(), any(), any(), eq(9L)))
                .thenAnswer(invocation -> {
                    String environment = invocation.getArgument(1);
                    if (environment.equals("local")) {
                        throw new IllegalStateException("install failed");
                    }
                    Thread.sleep(100);
                    return logOf(environment, invocation.getArgument(2));
                });

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> service.reconcilePlugin(
                1L, "akismet", List.of(
                        new StateChangeRequest("local", "INACTIVE"),
                        new StateChangeRequest("test", "INACTIVE")), 9L));

        assertEquals("install failed", thrown.getMessage());
        verify(bulkManagementService).applyToEnvironment(
                1L, "test", BulkOperationType.PLUGIN_INSTALL, "akismet", null, null, null, null, 9L);
    }

    @Test
    void reconcilePlugin_同じ環境を複数指定しても環境内は直列で実行される() {
        PluginThemeComparisonService service = service();
        bindThreeManagedSites();
        when(bulkManagementClient.listPlugins(any())).thenReturn(List.of());
        java.util.concurrent.atomic.AtomicInteger running = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger maxRunning = new java.util.concurrent.atomic.AtomicInteger();
        when(bulkManagementService.applyToEnvironment(
                eq(1L), eq("local"), any(), eq("akismet"), any(), any(), any(), any(), eq(9L)))
                .thenAnswer(invocation -> {
                    maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
                    Thread.sleep(50);
                    running.decrementAndGet();
                    return logOf("local", invocation.getArgument(2));
                });

        List<BulkOperationLog> results = service.reconcilePlugin(1L, "akismet", List.of(
                new StateChangeRequest("local", "INACTIVE"),
                new StateChangeRequest("local", "INACTIVE")), 9L);

        assertEquals(1, maxRunning.get());
        assertEquals(2, results.size());
    }

    @Test
    void reconcilePlugin_サポート外の遷移を含む場合は他の変更も実行せず例外() {
        PluginThemeComparisonService service = service();
        bindThreeManagedSites();
        when(bulkManagementClient.listPlugins("local-site")).thenReturn(List.of());
        when(bulkManagementClient.listPlugins("test-site"))
                .thenReturn(List.of(new PluginThemeInfo("akismet", "active")));
        when(bulkManagementClient.listPlugins("production-site")).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class, () -> service.reconcilePlugin(1L, "akismet", List.of(
                new StateChangeRequest("local", "ACTIVE"),
                new StateChangeRequest("test", "NOT_INSTALLED")), 9L));

        verify(bulkManagementService, never()).applyToEnvironment(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 一覧取得_後続環境の解決が失敗しても_投入済みのagent取得を待ち終えてから例外を投げる() {
        PluginThemeComparisonService service = service();
        Project project = buildProject(10L, 20L, null, "test");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(buildManagedSite(10L, "local-site")));
        when(siteService.getById(20L)).thenThrow(new IllegalStateException("resolve failed"));
        java.util.concurrent.atomic.AtomicBoolean fetchFinished = new java.util.concurrent.atomic.AtomicBoolean();
        when(bulkManagementClient.listPlugins("local-site")).thenAnswer(invocation -> {
            Thread.sleep(300);
            fetchFinished.set(true);
            return List.of();
        });

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.listPluginComparison(1L, 0, 20));

        assertEquals("resolve failed", thrown.getMessage());
        assertTrue(fetchFinished.get(), "投入済みのagent取得が終わる前に例外が呼び出し元へ伝わった(ワーカーが孤児になる)");
    }
}
