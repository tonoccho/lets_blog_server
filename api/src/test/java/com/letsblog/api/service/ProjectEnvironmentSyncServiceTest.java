package com.letsblog.api.service;

import com.letsblog.api.cms.CmsType;
import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.Site;
import com.letsblog.api.provisioning.WordPressSyncClient;
import com.letsblog.api.repository.ProjectRepository;
import com.letsblog.api.repository.SiteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectEnvironmentSyncServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private SiteRepository siteRepository;

    @Mock
    private WordPressSyncClient syncClient;

    private ProjectEnvironmentSyncService service() {
        return new ProjectEnvironmentSyncService(projectRepository, siteRepository, syncClient);
    }

    private Project buildProject(Long localSiteId, Long testSiteId, Long productionSiteId) {
        Project project = new Project();
        project.setId(1L);
        project.setName("テスト");
        project.setSlug("test");
        project.setLocalSiteId(localSiteId);
        project.setTestSiteId(testSiteId);
        project.setProductionSiteId(productionSiteId);
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
    void sync_正常系でWordPressSyncClientへslug_dbName_targetsを渡す() {
        ProjectEnvironmentSyncService service = service();
        Project project = buildProject(10L, 20L, null);
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteRepository.findById(20L)).thenReturn(Optional.of(testSite));

        service.sync(1L, "local", "test", List.of("themes", "db"));

        ArgumentCaptor<WordPressSyncClient.SyncCommand> captor =
                ArgumentCaptor.forClass(WordPressSyncClient.SyncCommand.class);
        verify(syncClient).sync(captor.capture());
        assertEquals("local-site", captor.getValue().fromSlug());
        assertEquals("wp_local-site", captor.getValue().fromDbName());
        assertEquals("test-site", captor.getValue().toSlug());
        assertEquals("wp_test-site", captor.getValue().toDbName());
        assertEquals(List.of("themes", "db"), captor.getValue().targets());
    }

    @Test
    void sync_mediaを含むtargetsもそのままWordPressSyncClientへ渡す() {
        ProjectEnvironmentSyncService service = service();
        Project project = buildProject(10L, 20L, null);
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteRepository.findById(20L)).thenReturn(Optional.of(testSite));

        service.sync(1L, "local", "test", List.of("themes", "plugins", "media", "db"));

        ArgumentCaptor<WordPressSyncClient.SyncCommand> captor =
                ArgumentCaptor.forClass(WordPressSyncClient.SyncCommand.class);
        verify(syncClient).sync(captor.capture());
        assertEquals(List.of("themes", "plugins", "media", "db"), captor.getValue().targets());
    }

    @Test
    void sync_同期元と同期先が同じ環境なら例外() {
        ProjectEnvironmentSyncService service = service();

        assertThrows(IllegalArgumentException.class, () -> service.sync(1L, "local", "local", List.of("db")));
    }

    @Test
    void sync_不正なenvironmentは例外() {
        ProjectEnvironmentSyncService service = service();

        assertThrows(IllegalArgumentException.class, () -> service.sync(1L, "invalid", "test", List.of("db")));
    }

    @Test
    void sync_環境にサイトが紐付いていなければ例外() {
        ProjectEnvironmentSyncService service = service();
        Project project = buildProject(null, 20L, null);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        assertThrows(IllegalArgumentException.class, () -> service.sync(1L, "local", "test", List.of("db")));
    }

    @Test
    void sync_非managedサイトが紐付いた環境は同期できない() {
        ProjectEnvironmentSyncService service = service();
        Project project = buildProject(10L, 20L, null);
        Site localSite = buildManagedSite(10L, "local-site");
        Site externalSite = new Site();
        externalSite.setId(20L);
        externalSite.setSiteKey("external-site");
        externalSite.setCmsType(CmsType.WORDPRESS);
        externalSite.setManagedWordpress(false);

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteRepository.findById(20L)).thenReturn(Optional.of(externalSite));

        assertThrows(IllegalArgumentException.class, () -> service.sync(1L, "local", "test", List.of("db")));
    }

    @Test
    void sync_プロジェクトが存在しなければ例外() {
        ProjectEnvironmentSyncService service = service();
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ProjectNotFoundException.class, () -> service.sync(99L, "local", "test", List.of("db")));
    }
}
