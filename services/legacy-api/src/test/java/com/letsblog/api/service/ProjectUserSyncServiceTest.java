package com.letsblog.api.service;

import com.letsblog.api.client.PublishingServiceClient;
import com.letsblog.api.client.PublishingServiceException;
import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.ProjectUser;
import com.letsblog.api.domain.Site;
import com.letsblog.api.domain.User;
import com.letsblog.api.dto.ProjectUserResponse;
import com.letsblog.api.repository.ProjectUserRepository;
import com.letsblog.api.repository.UserRepository;
import com.letsblog.api.repository.UserSiteAuthorRepository;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 著者(WordPressユーザー)作成/更新の実処理がpublishing-serviceへ移管された(issue #707、#575設計
 * 判断4の書き込み側)ため、検証はCmsAdapter/CmsAdapterFactoryではなくPublishingServiceClient
 * (内部ブリッジ)のモックへ差し替えている。
 */
@ExtendWith(MockitoExtension.class)
class ProjectUserSyncServiceTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private ProjectUserRepository projectUserRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SiteService siteService;

    @Mock
    private PublishingServiceClient publishingServiceClient;

    @Mock
    private UserSiteAuthorRepository userSiteAuthorRepository;

    private ProjectUserSyncService service() {
        return new ProjectUserSyncService(
                projectService, projectUserRepository, userRepository, siteService,
                publishingServiceClient, userSiteAuthorRepository);
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
        return site;
    }

    @Test
    void addUserToProject_複数環境への登録() {
        ProjectUserSyncService service = service();
        Project project = buildProject(10L, 20L, null);
        User user = buildUser();
        Site localSite = buildSite(10L, "local-site");
        Site testSite = buildSite(20L, "test-site");

        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(siteService.getAllById(List.of(10L, 20L))).thenReturn(List.of(localSite, testSite));
        when(publishingServiceClient.provisionAuthor(anyString(), any()))
                .thenReturn(new PublishingServiceClient.AuthorProvisioningResponse("9"));
        when(userSiteAuthorRepository.findByUserIdAndSiteId(any(), any())).thenReturn(Optional.empty());

        service.addUserToProject(1L, 2L, "editor");

        verify(publishingServiceClient, times(2)).provisionAuthor(anyString(), any());
        verify(projectUserRepository).save(any(ProjectUser.class));
        verify(userSiteAuthorRepository, times(2)).save(any(com.letsblog.api.domain.UserSiteAuthor.class));
    }

    @Test
    void addUserToProject_環境スロット未設定の場合は登録処理なし() {
        ProjectUserSyncService service = service();
        Project project = buildProject(null, null, null);
        User user = buildUser();

        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));

        service.addUserToProject(1L, 2L, "editor");

        verify(publishingServiceClient, never()).provisionAuthor(anyString(), any());
        verify(projectUserRepository).save(any(ProjectUser.class));
    }

    @Test
    void addUserToProject_wp_role指定がWordPressへ反映される() {
        ProjectUserSyncService service = service();
        Project project = buildProject(10L, null, null);
        User user = buildUser();
        Site localSite = buildSite(10L, "local-site");

        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(siteService.getAllById(List.of(10L))).thenReturn(List.of(localSite));
        when(publishingServiceClient.provisionAuthor(eq("local-site"), any()))
                .thenReturn(new PublishingServiceClient.AuthorProvisioningResponse("9"));

        service.addUserToProject(1L, 2L, "contributor");

        ArgumentCaptor<PublishingServiceClient.AuthorProvisioningRequest> captor =
                ArgumentCaptor.forClass(PublishingServiceClient.AuthorProvisioningRequest.class);
        verify(publishingServiceClient).provisionAuthor(eq("local-site"), captor.capture());
        assertEquals("contributor", captor.getValue().wpRole());
        assertEquals("member@example.com", captor.getValue().email());
    }

    @Test
    void addUserToProject_権限がないサイトはブリッジ呼び出し失敗として例外() {
        ProjectUserSyncService service = service();
        Project project = buildProject(10L, null, null);
        User user = buildUser();
        Site localSite = buildSite(10L, "local-site");

        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(siteService.getAllById(List.of(10L))).thenReturn(List.of(localSite));
        when(publishingServiceClient.provisionAuthor(eq("local-site"), any()))
                .thenThrow(new PublishingServiceException("権限がありません", null));

        assertThrows(PublishingServiceException.class, () -> service.addUserToProject(1L, 2L, "editor"));

        verify(projectUserRepository, never()).save(any());
    }

    @Test
    void updateUserProjectRole_ロール変更() {
        ProjectUserSyncService service = service();
        ProjectUser projectUser = new ProjectUser(1L, 2L, "editor");
        Project project = buildProject(10L, null, null);
        User user = buildUser();
        Site localSite = buildSite(10L, "local-site");

        when(projectUserRepository.findByProjectIdAndUserId(1L, 2L)).thenReturn(Optional.of(projectUser));
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(siteService.getAllById(List.of(10L))).thenReturn(List.of(localSite));
        when(publishingServiceClient.provisionAuthor(eq("local-site"), any()))
                .thenReturn(new PublishingServiceClient.AuthorProvisioningResponse("9"));

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
        verify(publishingServiceClient, never()).provisionAuthor(anyString(), any());
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
    void reconcileRolesForSite_参加中の全ユーザーのロールをproject_usersの内容で上書きする() {
        ProjectUserSyncService service = service();
        Site site = buildSite(10L, "local-site");
        User member = buildUser();
        User owner = new User();
        owner.setId(3L);
        owner.setEmail("owner@example.com");
        owner.setDisplayName("オーナー");
        ProjectUser memberLink = new ProjectUser(1L, 2L, "author");
        ProjectUser ownerLink = new ProjectUser(1L, 3L, "administrator");

        when(siteService.getById(10L)).thenReturn(Optional.of(site));
        when(projectUserRepository.findByProjectId(1L)).thenReturn(List.of(memberLink, ownerLink));
        when(userRepository.findById(2L)).thenReturn(Optional.of(member));
        when(userRepository.findById(3L)).thenReturn(Optional.of(owner));
        when(publishingServiceClient.provisionAuthor(eq("local-site"), any()))
                .thenReturn(new PublishingServiceClient.AuthorProvisioningResponse("9"));

        service.reconcileRolesForSite(1L, 10L);

        ArgumentCaptor<PublishingServiceClient.AuthorProvisioningRequest> captor =
                ArgumentCaptor.forClass(PublishingServiceClient.AuthorProvisioningRequest.class);
        verify(publishingServiceClient, times(2)).provisionAuthor(eq("local-site"), captor.capture());
        List<PublishingServiceClient.AuthorProvisioningRequest> requests = captor.getAllValues();
        assertEquals("author", requests.get(0).wpRole());
        assertEquals("member@example.com", requests.get(0).email());
        assertEquals("administrator", requests.get(1).wpRole());
        assertEquals("owner@example.com", requests.get(1).email());
    }

    @Test
    void reconcileRolesForSite_存在しないユーザーはスキップする() {
        ProjectUserSyncService service = service();
        Site site = buildSite(10L, "local-site");
        ProjectUser staleLink = new ProjectUser(1L, 99L, "editor");

        when(siteService.getById(10L)).thenReturn(Optional.of(site));
        when(projectUserRepository.findByProjectId(1L)).thenReturn(List.of(staleLink));
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        service.reconcileRolesForSite(1L, 10L);

        verify(publishingServiceClient, never()).provisionAuthor(anyString(), any());
    }

    @Test
    void reconcileRolesForSite_サイトが存在しなければ例外() {
        ProjectUserSyncService service = service();
        when(siteService.getById(10L)).thenReturn(Optional.empty());

        assertThrows(SiteNotFoundException.class, () -> service.reconcileRolesForSite(1L, 10L));
        verify(projectUserRepository, never()).findByProjectId(any());
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
