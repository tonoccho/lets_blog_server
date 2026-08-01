package com.letsblog.api.service;

import com.letsblog.api.cms.CmsType;
import com.letsblog.api.domain.BulkOperationLog;
import com.letsblog.api.domain.BulkOperationType;
import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.Site;
import com.letsblog.api.dto.ReconcileStateRequest.StateChangeRequest;
import com.letsblog.api.dto.StatusComparisonPage;
import com.letsblog.api.provisioning.WordPressBulkManagementClient;
import com.letsblog.api.provisioning.WordPressBulkManagementClient.PluginThemeInfo;
import com.letsblog.api.repository.ProjectRepository;
import com.letsblog.api.repository.SiteRepository;
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
    private ProjectRepository projectRepository;

    @Mock
    private SiteRepository siteRepository;

    @Mock
    private WordPressBulkManagementClient bulkManagementClient;

    @Mock
    private BulkManagementService bulkManagementService;

    private PluginThemeComparisonService service() {
        return new PluginThemeComparisonService(projectRepository, siteRepository, bulkManagementClient, bulkManagementService);
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
        site.setWpDbName("wp_" + slug);
        return site;
    }

    @Test
    void listPluginComparison_未紐付け環境はavailable_falseになり_未インストールはNOT_INSTALLEDになる() {
        PluginThemeComparisonService service = service();
        Project project = buildProject(10L, 20L, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteRepository.findById(20L)).thenReturn(Optional.of(testSite));
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
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
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
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
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
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
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
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteRepository.findById(20L)).thenReturn(Optional.of(testSite));
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
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.listPlugins("local-site")).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class, () -> service.deletePluginEverywhere(1L, "akismet", 9L));
    }
}
