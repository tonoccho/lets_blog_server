package com.letsblog.project.service;

import com.letsblog.project.domain.Project;
import com.letsblog.project.dto.ProjectResponse;
import com.letsblog.project.dto.UpdateProjectGithubRepositoryRequest;
import com.letsblog.project.messaging.DomainEventPublisher;
import com.letsblog.project.repository.ProjectRepository;
import com.letsblog.project.repository.SiteRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ProjectServiceの回帰テスト(issue #577 stage2、legacy-apiから移設)。 */
@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private SiteRepository siteRepository;
    @Mock
    private SiteService siteService;
    @Mock
    private DomainEventPublisher domainEventPublisher;

    private ProjectService service() {
        return new ProjectService(projectRepository, siteRepository, siteService, domainEventPublisher);
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
    void bindEnvironment_サイトが既に他プロジェクトに紐付いていれば例外() {
        Project project = new Project();
        project.setId(1L);
        Project other = new Project();
        other.setId(2L);
        other.setName("他のプロジェクト");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.existsById(10L)).thenReturn(true);
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
    void findProjectIdBySiteId_未紐付けはnull() {
        when(projectRepository.findByLocalSiteIdOrTestSiteIdOrProductionSiteId(10L, 10L, 10L))
                .thenReturn(Optional.empty());

        assertEquals(null, service().findProjectIdBySiteId(10L));
    }

    @Test
    void listProjects_デフォルトはcreatedAt降順() {
        when(projectRepository.findAll()).thenReturn(new java.util.ArrayList<>());

        List<ProjectResponse> result = service().listProjects(null, null);

        assertEquals(0, result.size());
    }

    @Test
    void deleteProject_削除後にproject_deletedイベントを発行する() {
        when(projectRepository.existsById(1L)).thenReturn(true);

        service().deleteProject(1L);

        verify(projectRepository).deleteById(1L);
        verify(domainEventPublisher).publishProjectDeleted(1L);
    }

    @Test
    void deleteProject_存在しなければイベントを発行せず例外() {
        when(projectRepository.existsById(99L)).thenReturn(false);

        assertThrows(ProjectNotFoundException.class, () -> service().deleteProject(99L));

        verify(domainEventPublisher, org.mockito.Mockito.never()).publishProjectDeleted(any());
    }

    // issue #1324: 環境紐付けをidentity-serviceへ通知する(既存メンバーのWordPressユーザー補填のため)。

    private Project projectWithId(Long id) {
        Project project = new Project();
        project.setId(id);
        return project;
    }

    private void stubBindable(Project project, Long siteId) {
        when(projectRepository.findById(project.getId())).thenReturn(Optional.of(project));
        when(siteRepository.existsById(siteId)).thenReturn(true);
        when(projectRepository.findByLocalSiteIdOrTestSiteIdOrProductionSiteId(siteId, siteId, siteId))
                .thenReturn(Optional.empty());
        when(projectRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void bindEnvironment_紐付けに成功するとprojectIdとsiteIdで環境紐付けイベントを発行する() {
        Project project = projectWithId(1L);
        stubBindable(project, 10L);

        service().bindEnvironment(1L, "test", 10L);

        assertEquals(10L, project.getTestSiteId());
        verify(domainEventPublisher).publishProjectEnvironmentBound(1L, 10L);
    }

    @Test
    void bindEnvironment_2つ目の環境を紐付けたときも新しいサイトでイベントを発行する() {
        Project project = projectWithId(1L);
        project.setTestSiteId(10L);
        stubBindable(project, 20L);

        service().bindEnvironment(1L, "production", 20L);

        verify(domainEventPublisher).publishProjectEnvironmentBound(1L, 20L);
    }

    @Test
    void bindEnvironment_検証に失敗したときはイベントを発行しない() {
        Project project = projectWithId(1L);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        assertThrows(IllegalArgumentException.class, () -> service().bindEnvironment(1L, "invalid", 10L));

        verify(domainEventPublisher, org.mockito.Mockito.never()).publishProjectEnvironmentBound(any(), any());
    }

    @Test
    void unbindEnvironment_解除ではイベントを発行しない() {
        Project project = projectWithId(1L);
        project.setTestSiteId(10L);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(projectRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().unbindEnvironment(1L, "test");

        verify(domainEventPublisher, org.mockito.Mockito.never()).publishProjectEnvironmentBound(any(), any());
    }
}
