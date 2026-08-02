package com.letsblog.api.service;

import com.letsblog.api.domain.Project;
import com.letsblog.api.dto.ProjectResponse;
import com.letsblog.api.repository.ProjectRepository;
import com.letsblog.api.repository.SiteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private SiteRepository siteRepository;

    @Mock
    private BulkUploadStorageService bulkUploadStorageService;

    private ProjectService service() {
        return new ProjectService(projectRepository, siteRepository, bulkUploadStorageService);
    }

    private Project buildProject(Long id, String slug) {
        Project project = new Project();
        project.setId(id);
        project.setName("テストプロジェクト");
        project.setSlug(slug);
        project.setCreatedAt(LocalDateTime.now());
        project.setUpdatedAt(LocalDateTime.now());
        return project;
    }

    @Test
    void createProject_正常に作成できる() {
        ProjectService service = service();
        when(projectRepository.existsBySlug("my-project")).thenReturn(false);
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> {
            Project p = invocation.getArgument(0);
            p.setId(1L);
            p.setCreatedAt(LocalDateTime.now());
            p.setUpdatedAt(LocalDateTime.now());
            return p;
        });

        ProjectResponse response = service.createProject("マイプロジェクト", "my-project");

        assertEquals("マイプロジェクト", response.name());
        assertEquals("my-project", response.slug());
        assertNull(response.localSite());
    }

    @Test
    void createProject_slug重複は例外() {
        ProjectService service = service();
        when(projectRepository.existsBySlug("dup")).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> service.createProject("重複", "dup"));
    }

    @Test
    void bindEnvironment_未使用サイトなら紐付できる() {
        ProjectService service = service();
        Project project = buildProject(1L, "proj-a");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.existsById(10L)).thenReturn(true);
        when(projectRepository.findByLocalSiteIdOrTestSiteIdOrProductionSiteId(10L, 10L, 10L))
                .thenReturn(Optional.empty());
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(siteRepository.findById(10L)).thenReturn(Optional.empty());

        ProjectResponse response = service.bindEnvironment(1L, "local", 10L);

        assertEquals(10L, project.getLocalSiteId());
        assertNull(response.localSite());
    }

    @Test
    void bindEnvironment_他プロジェクトが使用中のサイトは紐付できない() {
        ProjectService service = service();
        Project project = buildProject(1L, "proj-a");
        Project otherProject = buildProject(2L, "proj-b");
        otherProject.setLocalSiteId(10L);

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.existsById(10L)).thenReturn(true);
        when(projectRepository.findByLocalSiteIdOrTestSiteIdOrProductionSiteId(10L, 10L, 10L))
                .thenReturn(Optional.of(otherProject));

        assertThrows(IllegalArgumentException.class, () -> service.bindEnvironment(1L, "local", 10L));
        verify(projectRepository, never()).save(any(Project.class));
    }

    @Test
    void bindEnvironment_不正なenvironmentは例外() {
        ProjectService service = service();
        Project project = buildProject(1L, "proj-a");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        assertThrows(IllegalArgumentException.class, () -> service.bindEnvironment(1L, "staging", 10L));
    }

    @Test
    void unbindEnvironment_紐付を解除できる() {
        ProjectService service = service();
        Project project = buildProject(1L, "proj-a");
        project.setTestSiteId(20L);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProjectResponse response = service.unbindEnvironment(1L, "test");

        assertNull(response.testSite());
        assertNull(project.getTestSiteId());
    }

    @Test
    void updateMasterEnvironment_testまたはproductionを設定できる() {
        ProjectService service = service();
        Project project = buildProject(1L, "proj-a");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProjectResponse response = service.updateMasterEnvironment(1L, "production");

        assertEquals("production", response.masterEnvironment());
        assertEquals("production", project.getMasterEnvironment());
    }

    @Test
    void updateMasterEnvironment_localは指定できない() {
        ProjectService service = service();

        assertThrows(IllegalArgumentException.class, () -> service.updateMasterEnvironment(1L, "local"));
        verify(projectRepository, never()).save(any(Project.class));
    }

    @Test
    void updateMasterEnvironment_不正な値は例外() {
        ProjectService service = service();

        assertThrows(IllegalArgumentException.class, () -> service.updateMasterEnvironment(1L, "invalid"));
    }

    @Test
    void deleteProject_存在しないプロジェクトは例外() {
        ProjectService service = service();
        when(projectRepository.existsById(99L)).thenReturn(false);

        assertThrows(ProjectNotFoundException.class, () -> service.deleteProject(99L));
    }

    @Test
    void deleteProject_存在すれば削除される() {
        ProjectService service = service();
        when(projectRepository.existsById(1L)).thenReturn(true);

        service.deleteProject(1L);

        verify(projectRepository, times(1)).deleteById(1L);
        verify(bulkUploadStorageService).deleteAll(1L);
    }
}
