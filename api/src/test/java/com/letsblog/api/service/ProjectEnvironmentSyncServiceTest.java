package com.letsblog.api.service;

import com.letsblog.api.cms.CmsCredentials.WordPressCredentials;
import com.letsblog.api.cms.CmsType;
import com.letsblog.api.cms.ssh.WordPressSshOperations;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectEnvironmentSyncServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private SiteRepository siteRepository;

    @Mock
    private SiteService siteService;

    @Mock
    private WordPressSyncClient syncClient;

    @Mock
    private WordPressSshOperations sshOperations;

    @Mock
    private ProjectUserSyncService projectUserSyncService;

    private ProjectEnvironmentSyncService service() {
        return new ProjectEnvironmentSyncService(
                projectRepository, siteRepository, siteService, syncClient, sshOperations, projectUserSyncService);
    }

    private Site buildSshSite(Long id, String slug, String baseUrl) {
        Site site = new Site();
        site.setId(id);
        site.setSiteKey(slug);
        site.setCmsType(CmsType.WORDPRESS);
        site.setBaseUrl(baseUrl);
        site.setManagedWordpress(false);
        return site;
    }

    private WordPressCredentials buildSshCredentials() {
        return new WordPressCredentials(
                "https://prod.example.com", null, "SSH",
                "prod.example.com", 22, "deploy", "/var/www/prod", "PRIVATE_KEY_PEM", null, null);
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

        service.sync(1L, "test", "local", List.of("themes", "db"));

        ArgumentCaptor<WordPressSyncClient.SyncCommand> captor =
                ArgumentCaptor.forClass(WordPressSyncClient.SyncCommand.class);
        verify(syncClient).sync(captor.capture());
        assertEquals("test-site", captor.getValue().fromSlug());
        assertEquals("wp_test-site", captor.getValue().fromDbName());
        assertEquals("local-site", captor.getValue().toSlug());
        assertEquals("wp_local-site", captor.getValue().toDbName());
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

        service.sync(1L, "test", "local", List.of("themes", "plugins", "media", "db"));

        ArgumentCaptor<WordPressSyncClient.SyncCommand> captor =
                ArgumentCaptor.forClass(WordPressSyncClient.SyncCommand.class);
        verify(syncClient).sync(captor.capture());
        assertEquals(List.of("themes", "plugins", "media", "db"), captor.getValue().targets());
    }

    @Test
    void sync_同期元がlocalなら例外() {
        ProjectEnvironmentSyncService service = service();

        assertThrows(IllegalArgumentException.class, () -> service.sync(1L, "local", "test", List.of("db")));
    }

    @Test
    void sync_同期先にlocalを指定するのは許可される() {
        ProjectEnvironmentSyncService service = service();
        Project project = buildProject(10L, 20L, null);
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteRepository.findById(20L)).thenReturn(Optional.of(testSite));

        service.sync(1L, "test", "local", List.of("db"));

        verify(syncClient).sync(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sync_同期先がproductionなら例外() {
        ProjectEnvironmentSyncService service = service();

        assertThrows(IllegalArgumentException.class, () -> service.sync(1L, "test", "production", List.of("db")));
    }

    @Test
    void sync_同期元にproductionを指定するのは許可される() {
        ProjectEnvironmentSyncService service = service();
        Project project = buildProject(10L, 20L, 30L);
        Site localSite = buildManagedSite(10L, "local-site");
        Site productionSite = buildManagedSite(30L, "production-site");

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteRepository.findById(30L)).thenReturn(Optional.of(productionSite));

        service.sync(1L, "production", "local", List.of("db"));

        verify(syncClient).sync(org.mockito.ArgumentMatchers.any());
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
        Project project = buildProject(10L, null, null);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        assertThrows(IllegalArgumentException.class, () -> service.sync(1L, "test", "local", List.of("db")));
    }

    @Test
    void sync_同期先が非managedサイトの環境は同期できない() {
        ProjectEnvironmentSyncService service = service();
        Project project = buildProject(10L, 20L, 30L);
        Site productionSite = buildManagedSite(30L, "production-site");
        Site externalTestSite = new Site();
        externalTestSite.setId(20L);
        externalTestSite.setSiteKey("external-site");
        externalTestSite.setCmsType(CmsType.WORDPRESS);
        externalTestSite.setManagedWordpress(false);

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(30L)).thenReturn(Optional.of(productionSite));
        when(siteRepository.findById(20L)).thenReturn(Optional.of(externalTestSite));

        assertThrows(IllegalArgumentException.class, () -> service.sync(1L, "production", "test", List.of("db")));
    }

    @Test
    void sync_同期元がSSH未設定の非managedサイトなら例外() {
        ProjectEnvironmentSyncService service = service();
        Project project = buildProject(10L, 20L, 30L);
        Site localSite = buildManagedSite(10L, "local-site");
        Site externalSite = new Site();
        externalSite.setId(30L);
        externalSite.setSiteKey("external-site");
        externalSite.setCmsType(CmsType.WORDPRESS);
        externalSite.setManagedWordpress(false);

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(30L)).thenReturn(Optional.of(externalSite));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.resolveDataSource(externalSite))
                .thenReturn(new SiteService.SiteDataSource(false, null));

        assertThrows(IllegalArgumentException.class, () -> service.sync(1L, "production", "local", List.of("db")));
        verify(syncClient, never()).importDatabase(any(), any(), any(), any(), any());
    }

    @Test
    void sync_SSH管理サイトを同期元にテーマを含めて同期できる() {
        ProjectEnvironmentSyncService service = service();
        Project project = buildProject(10L, 20L, 30L);
        Site localSite = buildManagedSite(10L, "local-site");
        Site sshSite = buildSshSite(30L, "production-site", "https://prod.example.com");
        WordPressCredentials sshCredentials = buildSshCredentials();
        byte[] dump = "-- dump --".getBytes();
        byte[] themes = "themes-tar-gz-bytes".getBytes();

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(30L)).thenReturn(Optional.of(sshSite));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.resolveDataSource(sshSite))
                .thenReturn(new SiteService.SiteDataSource(false, sshCredentials));
        when(sshOperations.exportDatabase(sshCredentials))
                .thenReturn(new WordPressSshOperations.DatabaseExport("jI7_", dump));
        when(sshOperations.exportThemes(sshCredentials)).thenReturn(themes);

        service.sync(1L, "production", "local", List.of("themes", "db"));

        verify(syncClient).importDatabase("local-site", "wp_local-site", "https://prod.example.com", "jI7_", dump);
        verify(syncClient).importThemes("local-site", themes);
        verify(sshOperations, never()).exportMedia(any());
    }

    @Test
    void sync_SSH管理サイトのテーマが空なら同期先へインポートしない() {
        ProjectEnvironmentSyncService service = service();
        Project project = buildProject(10L, 20L, 30L);
        Site localSite = buildManagedSite(10L, "local-site");
        Site sshSite = buildSshSite(30L, "production-site", "https://prod.example.com");
        WordPressCredentials sshCredentials = buildSshCredentials();

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(30L)).thenReturn(Optional.of(sshSite));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.resolveDataSource(sshSite))
                .thenReturn(new SiteService.SiteDataSource(false, sshCredentials));
        when(sshOperations.exportThemes(sshCredentials)).thenReturn(new byte[0]);

        service.sync(1L, "production", "local", List.of("themes"));

        verify(syncClient, never()).importThemes(any(), any());
    }

    @Test
    void sync_SSH管理サイトを同期元にDBのみ同期できる() {
        ProjectEnvironmentSyncService service = service();
        Project project = buildProject(10L, 20L, 30L);
        Site localSite = buildManagedSite(10L, "local-site");
        Site sshSite = buildSshSite(30L, "production-site", "https://prod.example.com");
        WordPressCredentials sshCredentials = buildSshCredentials();
        byte[] dump = "-- dump --".getBytes();

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(30L)).thenReturn(Optional.of(sshSite));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.resolveDataSource(sshSite))
                .thenReturn(new SiteService.SiteDataSource(false, sshCredentials));
        when(sshOperations.exportDatabase(sshCredentials))
                .thenReturn(new WordPressSshOperations.DatabaseExport("jI7_", dump));

        service.sync(1L, "production", "local", List.of("db"));

        verify(sshOperations).exportDatabase(sshCredentials);
        verify(sshOperations, never()).exportMedia(any());
        verify(syncClient).importDatabase("local-site", "wp_local-site", "https://prod.example.com", "jI7_", dump);
        verify(syncClient, never()).importMedia(any(), any());
        verify(syncClient, never()).sync(any());
    }

    @Test
    void sync_SSH管理サイトを同期元にDBとメディアを同期できる() {
        ProjectEnvironmentSyncService service = service();
        Project project = buildProject(10L, 20L, 30L);
        Site localSite = buildManagedSite(10L, "local-site");
        Site sshSite = buildSshSite(30L, "production-site", "https://prod.example.com");
        WordPressCredentials sshCredentials = buildSshCredentials();
        byte[] dump = "-- dump --".getBytes();
        byte[] media = "tar-gz-bytes".getBytes();

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(30L)).thenReturn(Optional.of(sshSite));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.resolveDataSource(sshSite))
                .thenReturn(new SiteService.SiteDataSource(false, sshCredentials));
        when(sshOperations.exportDatabase(sshCredentials))
                .thenReturn(new WordPressSshOperations.DatabaseExport("jI7_", dump));
        when(sshOperations.exportMedia(sshCredentials)).thenReturn(media);

        service.sync(1L, "production", "local", List.of("db", "media"));

        verify(syncClient).importDatabase("local-site", "wp_local-site", "https://prod.example.com", "jI7_", dump);
        verify(syncClient).importMedia("local-site", media);
    }

    @Test
    void sync_SSH管理サイトのメディアが空なら同期先へインポートしない() {
        ProjectEnvironmentSyncService service = service();
        Project project = buildProject(10L, 20L, 30L);
        Site localSite = buildManagedSite(10L, "local-site");
        Site sshSite = buildSshSite(30L, "production-site", "https://prod.example.com");
        WordPressCredentials sshCredentials = buildSshCredentials();

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(30L)).thenReturn(Optional.of(sshSite));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.resolveDataSource(sshSite))
                .thenReturn(new SiteService.SiteDataSource(false, sshCredentials));
        when(sshOperations.exportMedia(sshCredentials)).thenReturn(new byte[0]);

        service.sync(1L, "production", "local", List.of("media"));

        verify(syncClient, never()).importMedia(any(), any());
    }

    @Test
    void sync_SSH管理サイトを同期元にする場合pluginsを含むtargetsは例外() {
        ProjectEnvironmentSyncService service = service();
        Project project = buildProject(10L, 20L, 30L);
        Site localSite = buildManagedSite(10L, "local-site");
        Site sshSite = buildSshSite(30L, "production-site", "https://prod.example.com");

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(30L)).thenReturn(Optional.of(sshSite));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));

        assertThrows(IllegalArgumentException.class,
                () -> service.sync(1L, "production", "local", List.of("plugins", "media")));
        verify(syncClient, never()).importMedia(any(), any());
    }

    @Test
    void sync_プロジェクトが存在しなければ例外() {
        ProjectEnvironmentSyncService service = service();
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ProjectNotFoundException.class, () -> service.sync(99L, "test", "local", List.of("db")));
    }

    @Test
    void sync_DBを同期対象に含む場合は同期先サイトのユーザーロールを整合させる() {
        ProjectEnvironmentSyncService service = service();
        Project project = buildProject(10L, 20L, null);
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteRepository.findById(20L)).thenReturn(Optional.of(testSite));

        service.sync(1L, "test", "local", List.of("themes", "db"));

        verify(projectUserSyncService).reconcileRolesForSite(1L, 10L);
    }

    @Test
    void sync_DBを同期対象に含まない場合はユーザーロールを整合させない() {
        ProjectEnvironmentSyncService service = service();
        Project project = buildProject(10L, 20L, null);
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteRepository.findById(20L)).thenReturn(Optional.of(testSite));

        service.sync(1L, "test", "local", List.of("themes"));

        verify(projectUserSyncService, never()).reconcileRolesForSite(any(), any());
    }

    @Test
    void sync_SSH管理サイトを同期元にDBを同期した場合もユーザーロールを整合させる() {
        ProjectEnvironmentSyncService service = service();
        Project project = buildProject(10L, 20L, 30L);
        Site localSite = buildManagedSite(10L, "local-site");
        Site sshSite = buildSshSite(30L, "production-site", "https://prod.example.com");
        WordPressCredentials sshCredentials = buildSshCredentials();
        byte[] dump = "-- dump --".getBytes();

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(30L)).thenReturn(Optional.of(sshSite));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.resolveDataSource(sshSite))
                .thenReturn(new SiteService.SiteDataSource(false, sshCredentials));
        when(sshOperations.exportDatabase(sshCredentials))
                .thenReturn(new WordPressSshOperations.DatabaseExport("jI7_", dump));

        service.sync(1L, "production", "local", List.of("db"));

        verify(projectUserSyncService).reconcileRolesForSite(1L, 10L);
    }
}
