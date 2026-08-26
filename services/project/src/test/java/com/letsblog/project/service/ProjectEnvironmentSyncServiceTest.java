package com.letsblog.project.service;

import com.letsblog.project.client.CmsProvisioningBridgeClient;
import com.letsblog.project.client.LegacyApiBridgeClient;
import com.letsblog.project.domain.Project;
import com.letsblog.project.domain.Site;
import com.letsblog.project.provisioning.WordPressSyncClient;
import com.letsblog.project.repository.ProjectRepository;
import com.letsblog.project.repository.SiteRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProjectEnvironmentSyncServiceの回帰テスト(issue #577 stage2、legacy-apiから移設)。
 * project_userテーブルはまだlegacy-apiに残るため、DB同期後のロール再整合が
 * LegacyApiBridgeClient経由でlegacy-apiへ依頼されることを検証する。
 */
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
    private CmsProvisioningBridgeClient bridgeClient;
    @Mock
    private LegacyApiBridgeClient legacyApiBridgeClient;
    @Mock
    private CurrentActorService currentActorService;

    private ProjectEnvironmentSyncService service() {
        return new ProjectEnvironmentSyncService(projectRepository, siteRepository, siteService, syncClient,
                bridgeClient, legacyApiBridgeClient, currentActorService);
    }

    @Test
    void sync_同期元と同期先が同じ環境なら例外() {
        assertThrows(IllegalArgumentException.class, () -> service().sync(1L, "test", "test", List.of("db")));
    }

    @Test
    void sync_ローカル環境は同期元にできない() {
        assertThrows(IllegalArgumentException.class, () -> service().sync(1L, "local", "test", List.of("db")));
    }

    @Test
    void sync_本番環境は同期先にできない() {
        assertThrows(IllegalArgumentException.class, () -> service().sync(1L, "test", "production", List.of("db")));
    }

    @Test
    void sync_managed同士はWordPressSyncClientで同期しDB同期後にロール再整合を依頼する() {
        Project project = new Project();
        project.setId(1L);
        project.setTestSiteId(10L);
        project.setLocalSiteId(20L);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        Site fromSite = new Site();
        fromSite.setId(10L);
        fromSite.setManagedWordpress(true);
        fromSite.setWpSlug("from-slug");
        fromSite.setWpDbName("wp_from");
        when(siteRepository.findById(10L)).thenReturn(Optional.of(fromSite));

        Site toSite = new Site();
        toSite.setId(20L);
        toSite.setManagedWordpress(true);
        toSite.setWpSlug("to-slug");
        toSite.setWpDbName("wp_to");
        when(siteRepository.findById(20L)).thenReturn(Optional.of(toSite));

        when(currentActorService.getAuthorizationHeader()).thenReturn("Bearer token");

        service().sync(1L, "test", "local", List.of("db", "themes"));

        verify(syncClient).sync(new WordPressSyncClient.SyncCommand(
                "from-slug", "wp_from", "to-slug", "wp_to", List.of("db", "themes")));
        verify(legacyApiBridgeClient).reconcileRolesForSite(1L, 20L, "Bearer token");
    }

    @Test
    void sync_同期先が自動構築サイトでなければ例外() {
        Project project = new Project();
        project.setId(1L);
        project.setTestSiteId(10L);
        project.setLocalSiteId(20L);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        Site fromSite = new Site();
        fromSite.setId(10L);
        fromSite.setManagedWordpress(true);
        when(siteRepository.findById(10L)).thenReturn(Optional.of(fromSite));

        Site toSite = new Site();
        toSite.setId(20L);
        toSite.setManagedWordpress(false);
        when(siteRepository.findById(20L)).thenReturn(Optional.of(toSite));

        assertThrows(IllegalArgumentException.class, () -> service().sync(1L, "test", "local", List.of("db")));
    }
}
