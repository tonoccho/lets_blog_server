package com.letsblog.api.service;

import com.letsblog.api.cms.AuthorProvisioningRequest;
import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsApiException;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.CmsType;
import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.ProjectUser;
import com.letsblog.api.domain.Site;
import com.letsblog.api.domain.User;
import com.letsblog.api.dto.ProjectUserResponse;
import com.letsblog.api.repository.ProjectRepository;
import com.letsblog.api.repository.ProjectUserRepository;
import com.letsblog.api.repository.SiteRepository;
import com.letsblog.api.repository.UserRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectUserSyncServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectUserRepository projectUserRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SiteRepository siteRepository;

    @Mock
    private SiteService siteService;

    @Mock
    private CmsAdapterFactory cmsAdapterFactory;

    @Mock
    private CmsAdapter cmsAdapter;

    private ProjectUserSyncService service() {
        return new ProjectUserSyncService(
                projectRepository, projectUserRepository, userRepository, siteRepository, siteService, cmsAdapterFactory);
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

    private User buildUser() {
        User user = new User();
        user.setId(2L);
        user.setEmail("member@example.com");
        user.setDisplayName("山田太郎");
        return user;
    }

    private Site buildSite(Long id, String siteKey) {
        Site site = new Site();
        site.setId(id);
        site.setSiteKey(siteKey);
        site.setCmsType(CmsType.WORDPRESS);
        return site;
    }

    @Test
    void addUserToProject_複数環境への登録() {
        ProjectUserSyncService service = service();
        Project project = buildProject(10L, 20L, null);
        User user = buildUser();
        Site localSite = buildSite(10L, "local-site");
        Site testSite = buildSite(20L, "test-site");
        CmsCredentials credentials = new CmsCredentials.WordPressCredentials("https://example.com", "admin", "pass");

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(siteRepository.findAllById(List.of(10L, 20L))).thenReturn(List.of(localSite, testSite));
        when(siteService.getCredentials(any())).thenReturn(credentials);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.hasAuthorProvisioningCapability(any())).thenReturn(true);

        service.addUserToProject(1L, 2L, "editor");

        verify(cmsAdapter, times(2)).provisionAuthor(eq(credentials), any(AuthorProvisioningRequest.class));
        verify(projectUserRepository).save(any(ProjectUser.class));
    }

    @Test
    void addUserToProject_環境スロット未設定の場合は登録処理なし() {
        ProjectUserSyncService service = service();
        Project project = buildProject(null, null, null);
        User user = buildUser();

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));

        service.addUserToProject(1L, 2L, "editor");

        verify(cmsAdapterFactory, never()).resolve(any());
        verify(projectUserRepository).save(any(ProjectUser.class));
    }

    @Test
    void addUserToProject_wp_role指定がWordPressへ反映される() {
        ProjectUserSyncService service = service();
        Project project = buildProject(10L, null, null);
        User user = buildUser();
        Site localSite = buildSite(10L, "local-site");
        CmsCredentials credentials = new CmsCredentials.WordPressCredentials("https://example.com", "admin", "pass");

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(siteRepository.findAllById(List.of(10L))).thenReturn(List.of(localSite));
        when(siteService.getCredentials("local-site")).thenReturn(credentials);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.hasAuthorProvisioningCapability(any())).thenReturn(true);

        service.addUserToProject(1L, 2L, "contributor");

        ArgumentCaptor<AuthorProvisioningRequest> captor = ArgumentCaptor.forClass(AuthorProvisioningRequest.class);
        verify(cmsAdapter).provisionAuthor(eq(credentials), captor.capture());
        assertEquals("contributor", captor.getValue().wpRole());
        assertEquals("member@example.com", captor.getValue().email());
    }

    @Test
    void addUserToProject_管理者権限がないサイトは著者作成前に例外() {
        ProjectUserSyncService service = service();
        Project project = buildProject(10L, null, null);
        User user = buildUser();
        Site localSite = buildSite(10L, "local-site");
        CmsCredentials credentials = new CmsCredentials.WordPressCredentials("https://example.com", "editor", "pass");

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(siteRepository.findAllById(List.of(10L))).thenReturn(List.of(localSite));
        when(siteService.getCredentials("local-site")).thenReturn(credentials);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.hasAuthorProvisioningCapability(credentials)).thenReturn(false);

        assertThrows(CmsApiException.class, () -> service.addUserToProject(1L, 2L, "editor"));

        verify(cmsAdapter, org.mockito.Mockito.never()).provisionAuthor(any(), any());
        verify(projectUserRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void updateUserProjectRole_ロール変更() {
        ProjectUserSyncService service = service();
        ProjectUser projectUser = new ProjectUser(1L, 2L, "editor");
        Project project = buildProject(10L, null, null);
        User user = buildUser();
        Site localSite = buildSite(10L, "local-site");
        CmsCredentials credentials = new CmsCredentials.WordPressCredentials("https://example.com", "admin", "pass");

        when(projectUserRepository.findByProjectIdAndUserId(1L, 2L)).thenReturn(Optional.of(projectUser));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(siteRepository.findAllById(List.of(10L))).thenReturn(List.of(localSite));
        when(siteService.getCredentials("local-site")).thenReturn(credentials);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.hasAuthorProvisioningCapability(any())).thenReturn(true);

        service.updateUserProjectRole(1L, 2L, "author");

        assertEquals("author", projectUser.getWpRole());
        verify(projectUserRepository).save(projectUser);
    }

    @Test
    void updateUserProjectRole_未参加ユーザーは例外() {
        ProjectUserSyncService service = service();
        when(projectUserRepository.findByProjectIdAndUserId(1L, 2L)).thenReturn(Optional.empty());

        assertThrows(ProjectUserNotFoundException.class, () -> service.updateUserProjectRole(1L, 2L, "author"));
    }

    @Test
    void removeUserFromProject_削除() {
        ProjectUserSyncService service = service();

        service.removeUserFromProject(1L, 2L);

        verify(projectUserRepository).deleteByProjectIdAndUserId(1L, 2L);
        verify(cmsAdapterFactory, never()).resolve(any());
    }

    @Test
    void getProjectUsers_一覧取得() {
        ProjectUserSyncService service = service();
        ProjectUser projectUser = new ProjectUser(1L, 2L, "editor");
        User user = buildUser();

        when(projectUserRepository.findByProjectId(1L)).thenReturn(List.of(projectUser));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));

        List<ProjectUserResponse> response = service.getProjectUsers(1L);

        assertEquals(1, response.size());
        assertEquals("member@example.com", response.get(0).email());
        assertEquals("editor", response.get(0).wpRole());
    }

    @Test
    void listAllProjectUsers_全プロジェクトの紐付けを返す() {
        ProjectUserSyncService service = service();
        ProjectUser pu1 = new ProjectUser(1L, 2L, "editor");
        ProjectUser pu2 = new ProjectUser(1L, 3L, "author");
        ProjectUser pu3 = new ProjectUser(2L, 2L, "contributor");

        when(projectUserRepository.findAll()).thenReturn(List.of(pu1, pu2, pu3));

        List<com.letsblog.api.dto.ProjectUserSummaryResponse> response = service.listAllProjectUsers();

        assertEquals(3, response.size());
        assertEquals(1L, response.get(0).projectId());
        assertEquals(2L, response.get(0).userId());
        assertEquals("editor", response.get(0).wpRole());
        assertEquals(2L, response.get(2).projectId());
        assertEquals("contributor", response.get(2).wpRole());
    }
}
