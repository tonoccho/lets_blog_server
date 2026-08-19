package com.letsblog.api.service;

import com.letsblog.api.domain.Project;
import com.letsblog.api.dto.ProjectResponse;
import com.letsblog.api.dto.UpdateArticleImageResizeDefaultRequest;
import com.letsblog.api.dto.UpdateImageGenerationPromptDefaultsRequest;
import com.letsblog.api.dto.UpdateImageGenerationSizeDefaultsRequest;
import com.letsblog.api.dto.UpdateProjectCssSelectorPrefixRequest;
import com.letsblog.api.dto.UpdateProjectGithubRepositoryRequest;
import com.letsblog.api.repository.ProjectRepository;
import com.letsblog.api.repository.SiteRepository;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        return new ProjectService(
                projectRepository, siteRepository, bulkUploadStorageService,
                "low quality, blurry, watermark, text", "high quality, highly detailed, sharp focus, masterpiece",
                1920, 1080, 1300);
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

    @Test
    void updateGithubRepository_owner_repo形式の値が正常に保存される() {
        ProjectService service = service();
        Project project = buildProject(1L, "proj-a");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProjectResponse response = service.updateGithubRepository(
                1L, new UpdateProjectGithubRepositoryRequest("anthropics/prompt-library"));

        assertEquals("anthropics/prompt-library", response.githubRepository());
        assertEquals("anthropics/prompt-library", project.getGithubRepository());
    }

    @Test
    void updateGithubRepository_空文字列はnullに変換される() {
        ProjectService service = service();
        Project project = buildProject(1L, "proj-a");
        project.setGithubRepository("owner/repo");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProjectResponse response = service.updateGithubRepository(1L, new UpdateProjectGithubRepositoryRequest(""));

        assertNull(response.githubRepository());
        assertNull(project.getGithubRepository());
    }

    @Test
    void updateGithubRepository_正規表現に違反する値はConstraintViolationExceptionをスロー() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            Set<jakarta.validation.ConstraintViolation<UpdateProjectGithubRepositoryRequest>> violations =
                    validator.validate(new UpdateProjectGithubRepositoryRequest("invalid-format"));

            assertFalse(violations.isEmpty());
            assertThrows(ConstraintViolationException.class, () -> {
                if (!violations.isEmpty()) {
                    throw new ConstraintViolationException(violations);
                }
            });
        }
    }

    @Test
    void updateCssSelectorPrefix_値が正常に保存される() {
        ProjectService service = service();
        Project project = buildProject(1L, "proj-a");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProjectResponse response = service.updateCssSelectorPrefix(
                1L, new UpdateProjectCssSelectorPrefixRequest("custom-prefix"));

        assertEquals("custom-prefix", response.cssSelectorPrefix());
        assertEquals("custom-prefix", project.getCssSelectorPrefix());
    }

    @Test
    void updateCssSelectorPrefix_空文字列はnullに変換される() {
        ProjectService service = service();
        Project project = buildProject(1L, "proj-a");
        project.setCssSelectorPrefix("custom-prefix");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProjectResponse response = service.updateCssSelectorPrefix(1L, new UpdateProjectCssSelectorPrefixRequest(""));

        assertNull(response.cssSelectorPrefix());
        assertNull(project.getCssSelectorPrefix());
    }

    @Test
    void resolveCssSelectorPrefix_未設定時はslugを返す() {
        ProjectService service = service();
        Project project = buildProject(1L, "proj-a");

        assertEquals("proj-a", service.resolveCssSelectorPrefix(project));
    }

    @Test
    void resolveCssSelectorPrefix_設定済みならその値を返す() {
        ProjectService service = service();
        Project project = buildProject(1L, "proj-a");
        project.setCssSelectorPrefix("custom-prefix");

        assertEquals("custom-prefix", service.resolveCssSelectorPrefix(project));
    }

    @Test
    void updateImageGenerationPromptDefaults_値が正常に保存される() {
        ProjectService service = service();
        Project project = buildProject(1L, "proj-a");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProjectResponse response = service.updateImageGenerationPromptDefaults(
                1L, new UpdateImageGenerationPromptDefaultsRequest("bad hands, extra fingers", "vivid colors"));

        assertEquals("bad hands, extra fingers", response.defaultNegativePrompt());
        assertEquals("vivid colors", response.defaultQualityPrompt());
    }

    @Test
    void updateImageGenerationPromptDefaults_空文字列はnullに変換される() {
        ProjectService service = service();
        Project project = buildProject(1L, "proj-a");
        project.setDefaultNegativePrompt("bad hands");
        project.setDefaultQualityPrompt("vivid colors");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProjectResponse response = service.updateImageGenerationPromptDefaults(
                1L, new UpdateImageGenerationPromptDefaultsRequest("", ""));

        assertNull(response.defaultNegativePrompt());
        assertNull(response.defaultQualityPrompt());
    }

    @Test
    void resolveDefaultNegativePrompt_projectId未指定ならグローバルデフォルトを返す() {
        ProjectService service = service();

        assertEquals("low quality, blurry, watermark, text", service.resolveDefaultNegativePrompt(null));
    }

    @Test
    void resolveDefaultNegativePrompt_プロジェクト未設定ならグローバルデフォルトを返す() {
        ProjectService service = service();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(buildProject(1L, "proj-a")));

        assertEquals("low quality, blurry, watermark, text", service.resolveDefaultNegativePrompt(1L));
    }

    @Test
    void resolveDefaultNegativePrompt_プロジェクト設定済みならその値を返す() {
        ProjectService service = service();
        Project project = buildProject(1L, "proj-a");
        project.setDefaultNegativePrompt("bad hands, extra fingers");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        assertEquals("bad hands, extra fingers", service.resolveDefaultNegativePrompt(1L));
    }

    @Test
    void resolveDefaultQualityPrompt_projectId未指定ならグローバルデフォルトを返す() {
        ProjectService service = service();

        assertEquals("high quality, highly detailed, sharp focus, masterpiece", service.resolveDefaultQualityPrompt(null));
    }

    @Test
    void resolveDefaultQualityPrompt_プロジェクト設定済みならその値を返す() {
        ProjectService service = service();
        Project project = buildProject(1L, "proj-a");
        project.setDefaultQualityPrompt("vivid colors, cinematic lighting");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        assertEquals("vivid colors, cinematic lighting", service.resolveDefaultQualityPrompt(1L));
    }

    @Test
    void updateImageGenerationSizeDefaults_値が正常に保存される() {
        ProjectService service = service();
        Project project = buildProject(1L, "proj-a");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProjectResponse response = service.updateImageGenerationSizeDefaults(
                1L, new UpdateImageGenerationSizeDefaultsRequest(1024, 768));

        assertEquals(1024, response.defaultGeneratedImageWidth());
        assertEquals(768, response.defaultGeneratedImageHeight());
    }

    @Test
    void updateImageGenerationSizeDefaults_nullを渡すとグローバルデフォルトへ戻る() {
        ProjectService service = service();
        Project project = buildProject(1L, "proj-a");
        project.setDefaultGeneratedImageWidth(1024);
        project.setDefaultGeneratedImageHeight(768);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProjectResponse response = service.updateImageGenerationSizeDefaults(
                1L, new UpdateImageGenerationSizeDefaultsRequest(null, null));

        assertNull(response.defaultGeneratedImageWidth());
        assertNull(response.defaultGeneratedImageHeight());
        assertEquals(1920, service.resolveDefaultGeneratedImageWidth(1L));
        assertEquals(1080, service.resolveDefaultGeneratedImageHeight(1L));
    }

    @Test
    void resolveDefaultGeneratedImageWidth_projectId未指定ならグローバルデフォルトを返す() {
        ProjectService service = service();

        assertEquals(1920, service.resolveDefaultGeneratedImageWidth(null));
        assertEquals(1080, service.resolveDefaultGeneratedImageHeight(null));
    }

    @Test
    void resolveDefaultGeneratedImageWidth_プロジェクト設定済みならその値を返す() {
        ProjectService service = service();
        Project project = buildProject(1L, "proj-a");
        project.setDefaultGeneratedImageWidth(1024);
        project.setDefaultGeneratedImageHeight(768);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        assertEquals(1024, service.resolveDefaultGeneratedImageWidth(1L));
        assertEquals(768, service.resolveDefaultGeneratedImageHeight(1L));
    }

    @Test
    void updateArticleImageResizeDefault_値が正常に保存される() {
        ProjectService service = service();
        Project project = buildProject(1L, "proj-a");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProjectResponse response = service.updateArticleImageResizeDefault(
                1L, new UpdateArticleImageResizeDefaultRequest(800));

        assertEquals(800, response.defaultArticleImageLongEdgePx());
    }

    @Test
    void updateArticleImageResizeDefault_nullを渡すとグローバルデフォルトへ戻る() {
        ProjectService service = service();
        Project project = buildProject(1L, "proj-a");
        project.setDefaultArticleImageLongEdgePx(800);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProjectResponse response = service.updateArticleImageResizeDefault(
                1L, new UpdateArticleImageResizeDefaultRequest(null));

        assertNull(response.defaultArticleImageLongEdgePx());
        assertEquals(1300, service.resolveArticleImageLongEdgePx(1L));
    }

    @Test
    void resolveArticleImageLongEdgePx_projectId未指定ならグローバルデフォルトを返す() {
        ProjectService service = service();

        assertEquals(1300, service.resolveArticleImageLongEdgePx(null));
    }

    @Test
    void resolveArticleImageLongEdgePx_プロジェクト設定済みならその値を返す() {
        ProjectService service = service();
        Project project = buildProject(1L, "proj-a");
        project.setDefaultArticleImageLongEdgePx(800);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        assertEquals(800, service.resolveArticleImageLongEdgePx(1L));
    }

    @Test
    void updateGithubRepository_owner_repo形式は制約違反にならない() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            Set<jakarta.validation.ConstraintViolation<UpdateProjectGithubRepositoryRequest>> violations =
                    validator.validate(new UpdateProjectGithubRepositoryRequest("owner/repo"));

            assertTrue(violations.isEmpty());
        }
    }
}
