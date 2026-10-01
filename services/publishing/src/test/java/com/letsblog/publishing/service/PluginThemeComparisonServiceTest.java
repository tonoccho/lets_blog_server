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
}
