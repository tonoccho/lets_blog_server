package com.letsblog.api.service;

import com.letsblog.api.client.ContentServiceClient;
import com.letsblog.api.client.ProjectServiceClient;
import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.ProjectImageSettings;
import com.letsblog.api.dto.ProjectResponse;
import com.letsblog.api.dto.UpdateArticleImageResizeDefaultRequest;
import com.letsblog.api.dto.UpdateImageContentFilterSettingsRequest;
import com.letsblog.api.dto.UpdateImageGenerationPromptDefaultsRequest;
import com.letsblog.api.dto.UpdateImageGenerationSizeDefaultsRequest;
import com.letsblog.api.dto.UpdateProjectCssSelectorPrefixRequest;
import com.letsblog.api.dto.UpdateProjectGithubRepositoryRequest;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProjectServiceの回帰テスト(issue #577 stage3)。プロジェクトのCRUD・環境紐付け・環境同期は
 * project-service側のProjectService(issue #577 stage2)が正となったため、legacy-api側のこのクラスは
 * project-serviceへの内部ブリッジ({@link ProjectServiceClient})経由でプロジェクトの基本情報を取得しつつ、
 * project-serviceには無い設定(CSSセレクタプリフィックス・画像生成デフォルト設定)の読み書きのみを担う。
 */
@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock
    private ProjectServiceClient projectServiceClient;

    @Mock
    private SiteService siteService;

    @Mock
    private ProjectImageSettingsService projectImageSettingsService;

    @Mock
    private ContentServiceClient contentServiceClient;

    private ProjectService service() {
        return new ProjectService(
                projectServiceClient, siteService,
                projectImageSettingsService, contentServiceClient,
                "low quality, blurry, watermark, text", "high quality, highly detailed, sharp focus, masterpiece",
                1920, 1080, 1300, true, true, true);
    }

    private ProjectServiceClient.ProjectBridge buildProjectBridge(Long id, String slug) {
        LocalDateTime now = LocalDateTime.now();
        return new ProjectServiceClient.ProjectBridge(id, "テストプロジェクト", slug, "test", null, null, null, null, now, now);
    }

    /** ProjectServiceのtoResponse()はimage/content設定を都度取得するため、既定でempty(未設定)を返すよう緩くstubする。 */
    private void stubEmptySettings() {
        lenient().when(projectImageSettingsService.findByProjectId(any())).thenReturn(Optional.empty());
        lenient().when(contentServiceClient.getCssSelectorPrefix(any())).thenReturn(null);
    }

    @Test
    void getProject_project_serviceの基本情報を転写する() {
        ProjectService service = service();
        stubEmptySettings();
        when(projectServiceClient.getProject(1L)).thenReturn(buildProjectBridge(1L, "my-project"));

        ProjectResponse response = service.getProject(1L);

        assertEquals("テストプロジェクト", response.name());
        assertEquals("my-project", response.slug());
        assertEquals("test", response.masterEnvironment());
        assertNull(response.localSite());
    }

    @Test
    void getProject_未登録ならProjectNotFoundException() {
        ProjectService service = service();
        when(projectServiceClient.getProject(99L)).thenThrow(new ProjectNotFoundException("id 99 のプロジェクトは登録されていません"));

        assertThrows(ProjectNotFoundException.class, () -> service.getProject(99L));
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
        stubEmptySettings();
        when(projectServiceClient.getProject(1L)).thenReturn(buildProjectBridge(1L, "proj-a"));

        service.updateCssSelectorPrefix(1L, new UpdateProjectCssSelectorPrefixRequest("custom-prefix"));

        verify(contentServiceClient).updateCssSelectorPrefix(1L, "custom-prefix");
    }

    @Test
    void updateCssSelectorPrefix_空文字列はnullに変換される() {
        ProjectService service = service();
        stubEmptySettings();
        when(projectServiceClient.getProject(1L)).thenReturn(buildProjectBridge(1L, "proj-a"));

        service.updateCssSelectorPrefix(1L, new UpdateProjectCssSelectorPrefixRequest(""));

        verify(contentServiceClient).updateCssSelectorPrefix(1L, null);
    }

    // resolveCssSelectorPrefix(カスタムタグCSSのセレクタプリフィックス解決)は、唯一の呼び出し元
    // だったCustomTagService/RenderedContentWrapperServiceがcontent-serviceへ移設されたため
    // ProjectServiceから削除した(issue #576)。同等のロジックのテストはcontent-service側の
    // ProjectContentSettingsServiceTestで検証する。

    @Test
    void updateImageGenerationPromptDefaults_値が正常に保存される() {
        ProjectService service = service();
        stubEmptySettings();
        when(projectServiceClient.getProject(1L)).thenReturn(buildProjectBridge(1L, "proj-a"));
        ProjectImageSettings saved = new ProjectImageSettings(1L);
        saved.setDefaultNegativePrompt("bad hands, extra fingers");
        saved.setDefaultQualityPrompt("vivid colors");
        when(projectImageSettingsService.updateImageGenerationPromptDefaults(1L, "bad hands, extra fingers", "vivid colors"))
                .thenReturn(saved);
        when(projectImageSettingsService.findByProjectId(1L)).thenReturn(Optional.of(saved));

        ProjectResponse response = service.updateImageGenerationPromptDefaults(
                1L, new UpdateImageGenerationPromptDefaultsRequest("bad hands, extra fingers", "vivid colors"));

        assertEquals("bad hands, extra fingers", response.defaultNegativePrompt());
        assertEquals("vivid colors", response.defaultQualityPrompt());
    }

    @Test
    void updateImageGenerationPromptDefaults_空文字列はnullに変換される() {
        ProjectService service = service();
        stubEmptySettings();
        when(projectServiceClient.getProject(1L)).thenReturn(buildProjectBridge(1L, "proj-a"));

        service.updateImageGenerationPromptDefaults(
                1L, new UpdateImageGenerationPromptDefaultsRequest("", ""));

        verify(projectImageSettingsService).updateImageGenerationPromptDefaults(1L, null, null);
    }

    @Test
    void resolveDefaultNegativePrompt_projectId未指定ならグローバルデフォルトを返す() {
        ProjectService service = service();

        assertEquals("low quality, blurry, watermark, text", service.resolveDefaultNegativePrompt(null));
    }

    @Test
    void resolveDefaultNegativePrompt_プロジェクト未設定ならグローバルデフォルトを返す() {
        ProjectService service = service();
        when(projectServiceClient.getProject(1L)).thenReturn(buildProjectBridge(1L, "proj-a"));
        when(projectImageSettingsService.findByProjectId(1L)).thenReturn(Optional.empty());

        assertEquals("low quality, blurry, watermark, text", service.resolveDefaultNegativePrompt(1L));
    }

    @Test
    void resolveDefaultNegativePrompt_プロジェクト設定済みならその値を返す() {
        ProjectService service = service();
        when(projectServiceClient.getProject(1L)).thenReturn(buildProjectBridge(1L, "proj-a"));
        ProjectImageSettings settings = new ProjectImageSettings(1L);
        settings.setDefaultNegativePrompt("bad hands, extra fingers");
        when(projectImageSettingsService.findByProjectId(1L)).thenReturn(Optional.of(settings));

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
        when(projectServiceClient.getProject(1L)).thenReturn(buildProjectBridge(1L, "proj-a"));
        ProjectImageSettings settings = new ProjectImageSettings(1L);
        settings.setDefaultQualityPrompt("vivid colors, cinematic lighting");
        when(projectImageSettingsService.findByProjectId(1L)).thenReturn(Optional.of(settings));

        assertEquals("vivid colors, cinematic lighting", service.resolveDefaultQualityPrompt(1L));
    }

    @Test
    void updateImageGenerationSizeDefaults_値が正常に保存される() {
        ProjectService service = service();
        stubEmptySettings();
        when(projectServiceClient.getProject(1L)).thenReturn(buildProjectBridge(1L, "proj-a"));
        ProjectImageSettings saved = new ProjectImageSettings(1L);
        saved.setDefaultGeneratedImageWidth(1024);
        saved.setDefaultGeneratedImageHeight(768);
        when(projectImageSettingsService.updateImageGenerationSizeDefaults(1L, 1024, 768)).thenReturn(saved);
        when(projectImageSettingsService.findByProjectId(1L)).thenReturn(Optional.of(saved));

        ProjectResponse response = service.updateImageGenerationSizeDefaults(
                1L, new UpdateImageGenerationSizeDefaultsRequest(1024, 768));

        assertEquals(1024, response.defaultGeneratedImageWidth());
        assertEquals(768, response.defaultGeneratedImageHeight());
    }

    @Test
    void updateImageGenerationSizeDefaults_nullを渡すとグローバルデフォルトへ戻る() {
        ProjectService service = service();
        stubEmptySettings();
        when(projectServiceClient.getProject(1L)).thenReturn(buildProjectBridge(1L, "proj-a"));

        ProjectResponse response = service.updateImageGenerationSizeDefaults(
                1L, new UpdateImageGenerationSizeDefaultsRequest(null, null));

        assertNull(response.defaultGeneratedImageWidth());
        assertNull(response.defaultGeneratedImageHeight());
        verify(projectImageSettingsService).updateImageGenerationSizeDefaults(1L, null, null);
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
        when(projectServiceClient.getProject(1L)).thenReturn(buildProjectBridge(1L, "proj-a"));
        ProjectImageSettings settings = new ProjectImageSettings(1L);
        settings.setDefaultGeneratedImageWidth(1024);
        settings.setDefaultGeneratedImageHeight(768);
        when(projectImageSettingsService.findByProjectId(1L)).thenReturn(Optional.of(settings));

        assertEquals(1024, service.resolveDefaultGeneratedImageWidth(1L));
        assertEquals(768, service.resolveDefaultGeneratedImageHeight(1L));
    }

    @Test
    void updateArticleImageResizeDefault_値が正常に保存される() {
        ProjectService service = service();
        stubEmptySettings();
        when(projectServiceClient.getProject(1L)).thenReturn(buildProjectBridge(1L, "proj-a"));
        ProjectImageSettings saved = new ProjectImageSettings(1L);
        saved.setDefaultArticleImageLongEdgePx(800);
        when(projectImageSettingsService.updateArticleImageResizeDefault(1L, 800)).thenReturn(saved);
        when(projectImageSettingsService.findByProjectId(1L)).thenReturn(Optional.of(saved));

        ProjectResponse response = service.updateArticleImageResizeDefault(
                1L, new UpdateArticleImageResizeDefaultRequest(800));

        assertEquals(800, response.defaultArticleImageLongEdgePx());
    }

    @Test
    void updateArticleImageResizeDefault_nullを渡すとグローバルデフォルトへ戻る() {
        ProjectService service = service();
        stubEmptySettings();
        when(projectServiceClient.getProject(1L)).thenReturn(buildProjectBridge(1L, "proj-a"));

        ProjectResponse response = service.updateArticleImageResizeDefault(
                1L, new UpdateArticleImageResizeDefaultRequest(null));

        assertNull(response.defaultArticleImageLongEdgePx());
        verify(projectImageSettingsService).updateArticleImageResizeDefault(1L, null);
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
        when(projectServiceClient.getProject(1L)).thenReturn(buildProjectBridge(1L, "proj-a"));
        ProjectImageSettings settings = new ProjectImageSettings(1L);
        settings.setDefaultArticleImageLongEdgePx(800);
        when(projectImageSettingsService.findByProjectId(1L)).thenReturn(Optional.of(settings));

        assertEquals(800, service.resolveArticleImageLongEdgePx(1L));
    }

    @Test
    void updateImageContentFilterSettings_値が正常に保存される() {
        ProjectService service = service();
        stubEmptySettings();
        when(projectServiceClient.getProject(1L)).thenReturn(buildProjectBridge(1L, "proj-a"));
        ProjectImageSettings saved = new ProjectImageSettings(1L);
        saved.setBlockSexualContent(false);
        saved.setBlockViolentContent(true);
        saved.setBlockDiscriminatoryContent(false);
        when(projectImageSettingsService.updateImageContentFilterSettings(1L, false, true, false)).thenReturn(saved);
        when(projectImageSettingsService.findByProjectId(1L)).thenReturn(Optional.of(saved));

        ProjectResponse response = service.updateImageContentFilterSettings(
                1L, new UpdateImageContentFilterSettingsRequest(false, true, false));

        assertEquals(false, response.blockSexualContent());
        assertEquals(true, response.blockViolentContent());
        assertEquals(false, response.blockDiscriminatoryContent());
    }

    @Test
    void resolveBlockSexualContent_projectId未指定ならグローバルデフォルトtrueを返す() {
        ProjectService service = service();

        assertTrue(service.resolveBlockSexualContent(null));
    }

    @Test
    void resolveBlockSexualContent_プロジェクト未設定ならグローバルデフォルトtrueを返す() {
        ProjectService service = service();
        when(projectServiceClient.getProject(1L)).thenReturn(buildProjectBridge(1L, "proj-a"));
        when(projectImageSettingsService.findByProjectId(1L)).thenReturn(Optional.empty());

        assertTrue(service.resolveBlockSexualContent(1L));
    }

    @Test
    void resolveBlockSexualContent_プロジェクト設定済みならその値を返す() {
        ProjectService service = service();
        when(projectServiceClient.getProject(1L)).thenReturn(buildProjectBridge(1L, "proj-a"));
        ProjectImageSettings settings = new ProjectImageSettings(1L);
        settings.setBlockSexualContent(false);
        when(projectImageSettingsService.findByProjectId(1L)).thenReturn(Optional.of(settings));

        assertFalse(service.resolveBlockSexualContent(1L));
    }

    @Test
    void resolveBlockViolentContent_プロジェクト設定済みならその値を返す() {
        ProjectService service = service();
        when(projectServiceClient.getProject(1L)).thenReturn(buildProjectBridge(1L, "proj-a"));
        ProjectImageSettings settings = new ProjectImageSettings(1L);
        settings.setBlockViolentContent(false);
        when(projectImageSettingsService.findByProjectId(1L)).thenReturn(Optional.of(settings));

        assertFalse(service.resolveBlockViolentContent(1L));
        assertTrue(service.resolveBlockViolentContent(null));
    }

    @Test
    void resolveBlockDiscriminatoryContent_プロジェクト設定済みならその値を返す() {
        ProjectService service = service();
        when(projectServiceClient.getProject(1L)).thenReturn(buildProjectBridge(1L, "proj-a"));
        ProjectImageSettings settings = new ProjectImageSettings(1L);
        settings.setBlockDiscriminatoryContent(false);
        when(projectImageSettingsService.findByProjectId(1L)).thenReturn(Optional.of(settings));

        assertFalse(service.resolveBlockDiscriminatoryContent(1L));
        assertTrue(service.resolveBlockDiscriminatoryContent(null));
    }

    @Test
    void findProjectIdBySiteId_project_serviceの逆引き結果を転写する() {
        ProjectService service = service();
        when(projectServiceClient.findProjectIdBySiteId(10L)).thenReturn(1L);

        assertEquals(1L, service.findProjectIdBySiteId(10L));
    }

}
