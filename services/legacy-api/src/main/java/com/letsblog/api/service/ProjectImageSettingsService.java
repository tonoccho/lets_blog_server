package com.letsblog.api.service;

import com.letsblog.api.domain.ProjectImageSettings;
import com.letsblog.api.repository.ProjectImageSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * プロジェクト単位の画像生成(media)関連設定(project_image_settings)の読み書きを扱う(issue #571)。
 * projects god-tableの分割で切り出された設定テーブルで、行は初回書き込み時に遅延作成する
 * (未設定のプロジェクトに空行を作らないため)。アプリ全体のデフォルト値へのフォールバックは
 * 呼び出し元(ProjectService/ImageModelService/ComfyUiModelService)が担う。
 */
@Service
public class ProjectImageSettingsService {

    private final ProjectImageSettingsRepository repository;

    public ProjectImageSettingsService(ProjectImageSettingsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<ProjectImageSettings> findByProjectId(Long projectId) {
        return repository.findByProjectId(projectId);
    }

    @Transactional
    public ProjectImageSettings getOrCreate(Long projectId) {
        return repository.findByProjectId(projectId)
                .orElseGet(() -> repository.save(new ProjectImageSettings(projectId)));
    }

    @Transactional(readOnly = true)
    public String getImageProvider(Long projectId) {
        return findByProjectId(projectId).map(ProjectImageSettings::getImageProvider).orElse(null);
    }

    @Transactional
    public void setImageProvider(Long projectId, String imageProvider) {
        ProjectImageSettings settings = getOrCreate(projectId);
        settings.setImageProvider(imageProvider);
        repository.save(settings);
    }

    @Transactional(readOnly = true)
    public String getComfyuiCheckpoint(Long projectId) {
        return findByProjectId(projectId).map(ProjectImageSettings::getComfyuiCheckpoint).orElse(null);
    }

    @Transactional
    public void setComfyuiCheckpoint(Long projectId, String comfyuiCheckpoint) {
        ProjectImageSettings settings = getOrCreate(projectId);
        settings.setComfyuiCheckpoint(comfyuiCheckpoint);
        repository.save(settings);
    }

    @Transactional
    public ProjectImageSettings updateImageGenerationPromptDefaults(
            Long projectId, String defaultNegativePrompt, String defaultQualityPrompt) {
        ProjectImageSettings settings = getOrCreate(projectId);
        settings.setDefaultNegativePrompt(defaultNegativePrompt);
        settings.setDefaultQualityPrompt(defaultQualityPrompt);
        return repository.save(settings);
    }

    @Transactional
    public ProjectImageSettings updateImageGenerationSizeDefaults(
            Long projectId, Integer defaultGeneratedImageWidth, Integer defaultGeneratedImageHeight) {
        ProjectImageSettings settings = getOrCreate(projectId);
        settings.setDefaultGeneratedImageWidth(defaultGeneratedImageWidth);
        settings.setDefaultGeneratedImageHeight(defaultGeneratedImageHeight);
        return repository.save(settings);
    }

    @Transactional
    public ProjectImageSettings updateArticleImageResizeDefault(Long projectId, Integer defaultArticleImageLongEdgePx) {
        ProjectImageSettings settings = getOrCreate(projectId);
        settings.setDefaultArticleImageLongEdgePx(defaultArticleImageLongEdgePx);
        return repository.save(settings);
    }

    @Transactional
    public ProjectImageSettings updateImageContentFilterSettings(
            Long projectId, Boolean blockSexualContent, Boolean blockViolentContent, Boolean blockDiscriminatoryContent) {
        ProjectImageSettings settings = getOrCreate(projectId);
        settings.setBlockSexualContent(blockSexualContent);
        settings.setBlockViolentContent(blockViolentContent);
        settings.setBlockDiscriminatoryContent(blockDiscriminatoryContent);
        return repository.save(settings);
    }
}
