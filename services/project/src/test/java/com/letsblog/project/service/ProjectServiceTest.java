package com.letsblog.project.service;

import com.letsblog.common.messaging.EventExchanges;
import com.letsblog.common.messaging.ProjectDeletedEvent;
import com.letsblog.common.messaging.ProjectEnvironmentBoundEvent;
import com.letsblog.project.domain.Project;
import com.letsblog.project.dto.ProjectResponse;
import com.letsblog.project.dto.UpdateProjectGithubRepositoryRequest;
import com.letsblog.project.repository.ProjectRepository;
import com.letsblog.project.testsupport.ProjectWorld;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProjectServiceの回帰テスト(issue #577 stage2、legacy-apiから移設)。
 *
 * <p>issue #1817: SiteService・DomainEventPublisher は実物。差し替えるのはDB(Spring Dataのリポジトリ)と
 * MQ({@code RabbitTemplate})だけで、発行したイベントは{@code RabbitTemplate}への送信で確かめる。
 */
@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    private final ProjectWorld world = new ProjectWorld();

    @AfterEach
    void close() {
        world.close();
    }

    private ProjectService service() {
        return new ProjectService(projectRepository, world.siteRepository, world.siteService,
                world.domainEventPublisher);
    }

    private Project project(Long id, String name, String slug, LocalDateTime createdAt) {
        Project project = new Project();
        project.setId(id);
        project.setName(name);
        project.setSlug(slug);
        project.setCreatedAt(createdAt);
        return project;
    }

    @Test
    void createProject_slugが既に使われていれば例外() {
        when(projectRepository.existsBySlug("test")).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> service().createProject("テスト", "test"));
    }

    @Test
    void createProject_成功時に保存してレスポンスを返す() {
        when(projectRepository.existsBySlug("test")).thenReturn(false);
        when(projectRepository.save(any())).thenAnswer(invocation -> {
            Project project = invocation.getArgument(0);
            project.setId(1L);
            return project;
        });

        ProjectResponse response = service().createProject("テスト", "test");

        assertEquals("テスト", response.name());
        assertEquals("test", response.slug());
    }

    @Test
    void getProject_存在しなければNotFound() {
        when(projectRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(ProjectNotFoundException.class, () -> service().getProject(1L));
    }

    @Test
    void getProject_紐付けたサイトをレスポンスに解決しSSH設定の有無も返す() {
        Project project = project(1L, "P", "p", null);
        project.setLocalSiteId(10L);
        project.setTestSiteId(20L);
        project.setProductionSiteId(99L);
        world.addSite(10L, "ローカル");
        world.addSite(20L, "テスト");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        ProjectResponse response = service().getProject(1L);

        assertEquals("ローカル", response.localSite().name());
        assertEquals("テスト", response.testSite().name());
        assertFalse(response.localSite().sshConfigured());
        assertNull(response.productionSite(), "登録の無いサイトは解決しない");
    }

    @Test
    void bindEnvironment_サイトが既に他プロジェクトに紐付いていれば例外() {
        Project project = new Project();
        project.setId(1L);
        Project other = new Project();
        other.setId(2L);
        other.setName("他のプロジェクト");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(world.siteRepository.existsById(10L)).thenReturn(true);
        when(projectRepository.findByLocalSiteIdOrTestSiteIdOrProductionSiteId(10L, 10L, 10L))
                .thenReturn(Optional.of(other));

        assertThrows(IllegalArgumentException.class, () -> service().bindEnvironment(1L, "local", 10L));
    }

    @Test
    void bindEnvironment_不正な環境名は例外() {
        Project project = new Project();
        project.setId(1L);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        assertThrows(IllegalArgumentException.class, () -> service().bindEnvironment(1L, "invalid", 10L));
    }

    @Test
    void bindEnvironment_登録の無いサイトはSiteNotFound() {
        Project project = new Project();
        project.setId(1L);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        assertThrows(SiteNotFoundException.class, () -> service().bindEnvironment(1L, "local", 10L));
    }

    @Test
    void updateGithubRepository_空文字はnullに変換して保存する() {
        Project project = new Project();
        project.setId(1L);
        project.setGithubRepository("owner/repo");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(projectRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().updateGithubRepository(1L, new UpdateProjectGithubRepositoryRequest(""));

        assertEquals(null, project.getGithubRepository());
    }

    @Test
    void updateGithubRepository_nullも空白もnullにし_値があればそのまま保存する() {
        Project project = new Project();
        project.setId(1L);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(projectRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().updateGithubRepository(1L, new UpdateProjectGithubRepositoryRequest(null));
        assertNull(project.getGithubRepository());
        service().updateGithubRepository(1L, new UpdateProjectGithubRepositoryRequest("  "));
        assertNull(project.getGithubRepository());
        service().updateGithubRepository(1L, new UpdateProjectGithubRepositoryRequest("owner/repo"));
        assertEquals("owner/repo", project.getGithubRepository());
    }

    @Test
    void findProjectIdBySiteId_未紐付けはnull() {
        when(projectRepository.findByLocalSiteIdOrTestSiteIdOrProductionSiteId(10L, 10L, 10L))
                .thenReturn(Optional.empty());

        assertEquals(null, service().findProjectIdBySiteId(10L));
    }

    @Test
    void findProjectIdBySiteId_紐付け済みならプロジェクトIDを返す() {
        when(projectRepository.findByLocalSiteIdOrTestSiteIdOrProductionSiteId(10L, 10L, 10L))
                .thenReturn(Optional.of(project(5L, "P", "p", null)));

        assertEquals(5L, service().findProjectIdBySiteId(10L));
    }

    @Test
    void listProjects_デフォルトはcreatedAt降順() {
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0);
        when(projectRepository.findAll()).thenReturn(new ArrayList<>(List.of(
                project(1L, "古い", "old", base), project(2L, "新しい", "new", base.plusDays(1)))));

        List<ProjectResponse> result = service().listProjects(null, null);

        assertEquals(List.of(2L, 1L), result.stream().map(ProjectResponse::id).toList());
    }

    @Test
    void listProjects_空の並び替え指定もデフォルトと同じ() {
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0);
        when(projectRepository.findAll()).thenReturn(new ArrayList<>(List.of(
                project(1L, "古い", "old", base), project(2L, "新しい", "new", base.plusDays(1)))));

        assertEquals(List.of(2L, 1L), service().listProjects(" ", "asc").stream().map(ProjectResponse::id).toList());
    }

    @Test
    void listProjects_名前_slug_作成日時で昇順降順に並べ_未知のキーは並べ替えない() {
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0);
        when(projectRepository.findAll()).thenAnswer(i -> new ArrayList<>(List.of(
                project(1L, "bbb", "zz", base), project(2L, "AAA", "yy", base.plusDays(2)),
                project(3L, "ccc", "xx", base.plusDays(1)))));

        assertEquals(List.of(2L, 1L, 3L), ids(service().listProjects("name", "asc")));
        assertEquals(List.of(3L, 1L, 2L), ids(service().listProjects("name", "desc")));
        assertEquals(List.of(3L, 2L, 1L), ids(service().listProjects("slug", null)));
        assertEquals(List.of(1L, 3L, 2L), ids(service().listProjects("createdAt", "ASC")));
        assertEquals(List.of(1L, 2L, 3L), ids(service().listProjects("unknown", "asc")));
    }

    private static List<Long> ids(List<ProjectResponse> responses) {
        return responses.stream().map(ProjectResponse::id).toList();
    }

    @Test
    void updateProject_名前を更新して保存する() {
        Project project = project(1L, "旧", "p", null);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(projectRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ProjectResponse response = service().updateProject(1L, "新");

        assertEquals("新", response.name());
        assertEquals("新", project.getName());
    }

    @Test
    void deleteProject_削除後にproject_deletedイベントを発行する() {
        when(projectRepository.existsById(1L)).thenReturn(true);

        service().deleteProject(1L);

        verify(projectRepository).deleteById(1L);
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(world.rabbitTemplate).convertAndSend(
                org.mockito.ArgumentMatchers.eq(EventExchanges.EVENTS_EXCHANGE),
                org.mockito.ArgumentMatchers.eq(EventExchanges.PROJECT_DELETED_ROUTING_KEY), event.capture());
        assertEquals(1L, ((ProjectDeletedEvent) event.getValue()).projectId());
    }

    @Test
    void deleteProject_存在しなければイベントを発行せず例外() {
        when(projectRepository.existsById(99L)).thenReturn(false);

        assertThrows(ProjectNotFoundException.class, () -> service().deleteProject(99L));

        verify(world.rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    // issue #1324: 環境紐付けをidentity-serviceへ通知する(既存メンバーのWordPressユーザー補填のため)。

    private Project projectWithId(Long id) {
        Project project = new Project();
        project.setId(id);
        return project;
    }

    private void stubBindable(Project project, Long siteId) {
        when(projectRepository.findById(project.getId())).thenReturn(Optional.of(project));
        when(world.siteRepository.existsById(siteId)).thenReturn(true);
        when(projectRepository.findByLocalSiteIdOrTestSiteIdOrProductionSiteId(siteId, siteId, siteId))
                .thenReturn(Optional.empty());
        when(projectRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void assertBoundEventPublished(Long projectId, Long siteId) {
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(world.rabbitTemplate).convertAndSend(
                org.mockito.ArgumentMatchers.eq(EventExchanges.EVENTS_EXCHANGE),
                org.mockito.ArgumentMatchers.eq(EventExchanges.PROJECT_ENVIRONMENT_BOUND_ROUTING_KEY), event.capture());
        ProjectEnvironmentBoundEvent bound = (ProjectEnvironmentBoundEvent) event.getValue();
        assertEquals(projectId, bound.projectId());
        assertEquals(siteId, bound.siteId());
    }

    @Test
    void bindEnvironment_紐付けに成功するとprojectIdとsiteIdで環境紐付けイベントを発行する() {
        Project project = projectWithId(1L);
        stubBindable(project, 10L);

        service().bindEnvironment(1L, "test", 10L);

        assertEquals(10L, project.getTestSiteId());
        assertBoundEventPublished(1L, 10L);
    }

    @Test
    void bindEnvironment_localとproductionにも紐付けられる() {
        Project project = projectWithId(1L);
        stubBindable(project, 10L);

        service().bindEnvironment(1L, "local", 10L);
        assertEquals(10L, project.getLocalSiteId());
        service().bindEnvironment(1L, "production", 10L);
        assertEquals(10L, project.getProductionSiteId());
    }

    @Test
    void bindEnvironment_同じプロジェクト自身に紐付いているサイトは再度紐付けられる() {
        Project project = projectWithId(1L);
        stubBindable(project, 10L);
        when(projectRepository.findByLocalSiteIdOrTestSiteIdOrProductionSiteId(10L, 10L, 10L))
                .thenReturn(Optional.of(project));

        service().bindEnvironment(1L, "test", 10L);

        assertEquals(10L, project.getTestSiteId());
    }

    @Test
    void bindEnvironment_2つ目の環境を紐付けたときも新しいサイトでイベントを発行する() {
        Project project = projectWithId(1L);
        project.setTestSiteId(10L);
        stubBindable(project, 20L);

        service().bindEnvironment(1L, "production", 20L);

        assertBoundEventPublished(1L, 20L);
    }

    @Test
    void bindEnvironment_検証に失敗したときはイベントを発行しない() {
        Project project = projectWithId(1L);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        assertThrows(IllegalArgumentException.class, () -> service().bindEnvironment(1L, "invalid", 10L));

        verify(world.rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    void unbindEnvironment_解除ではイベントを発行しない() {
        Project project = projectWithId(1L);
        project.setTestSiteId(10L);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(projectRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().unbindEnvironment(1L, "test");

        assertNull(project.getTestSiteId());
        verify(world.rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    void unbindEnvironment_localとproductionも外せ_不正な環境名は拒否する() {
        Project project = projectWithId(1L);
        project.setLocalSiteId(1L);
        project.setProductionSiteId(3L);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(projectRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().unbindEnvironment(1L, "local");
        service().unbindEnvironment(1L, "production");
        assertNull(project.getLocalSiteId());
        assertNull(project.getProductionSiteId());
        assertThrows(IllegalArgumentException.class, () -> service().unbindEnvironment(1L, "staging"));
    }

    @Test
    void updateMasterEnvironment_testかproductionだけを受け付ける() {
        Project project = projectWithId(1L);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(projectRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals("production", service().updateMasterEnvironment(1L, "production").masterEnvironment());
        assertEquals("test", service().updateMasterEnvironment(1L, "test").masterEnvironment());
        assertThrows(IllegalArgumentException.class, () -> service().updateMasterEnvironment(1L, "local"));
    }

    @Test
    void siteIdsOfProjects_プロジェクト群に紐付いたサイトIDだけを集める() {
        Project first = projectWithId(1L);
        first.setLocalSiteId(10L);
        first.setProductionSiteId(30L);
        Project second = projectWithId(2L);
        second.setTestSiteId(20L);
        when(projectRepository.findAllById(Set.of(1L, 2L))).thenReturn(List.of(first, second));

        assertEquals(Set.of(10L, 20L, 30L), service().siteIdsOfProjects(Set.of(1L, 2L)));
    }

    @Test
    void GitHubトークンの暗号化済みバイト列を取得し設定できる() {
        Project project = projectWithId(1L);
        project.setGithubTokenEncrypted(new byte[] {1, 2});
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        assertArrayEquals(new byte[] {1, 2}, service().getGithubTokenEncrypted(1L));
        service().setGithubTokenEncrypted(1L, new byte[] {9});

        assertArrayEquals(new byte[] {9}, project.getGithubTokenEncrypted());
        verify(projectRepository).save(project);
    }
}
