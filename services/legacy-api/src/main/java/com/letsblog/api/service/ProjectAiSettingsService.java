package com.letsblog.api.service;

import com.letsblog.api.domain.ProjectAiSettings;
import com.letsblog.api.repository.ProjectAiSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * プロジェクト単位のAI(LLM)関連設定(project_ai_settings)の読み書きを扱う(issue #571)。
 * projects god-tableの分割で切り出された設定テーブルで、行は初回書き込み時に遅延作成する
 * (未設定のプロジェクトに空行を作らないため)。
 */
@Service
public class ProjectAiSettingsService {

    private final ProjectAiSettingsRepository repository;

    public ProjectAiSettingsService(ProjectAiSettingsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<ProjectAiSettings> findByProjectId(Long projectId) {
        return repository.findByProjectId(projectId);
    }

    @Transactional
    public ProjectAiSettings getOrCreate(Long projectId) {
        return repository.findByProjectId(projectId)
                .orElseGet(() -> repository.save(new ProjectAiSettings(projectId)));
    }

    @Transactional(readOnly = true)
    public String getLlmModel(Long projectId) {
        return findByProjectId(projectId).map(ProjectAiSettings::getLlmModel).orElse(null);
    }

    @Transactional
    public void setLlmModel(Long projectId, String llmModel) {
        ProjectAiSettings settings = getOrCreate(projectId);
        settings.setLlmModel(llmModel);
        repository.save(settings);
    }

    @Transactional(readOnly = true)
    public String getLlmProvider(Long projectId) {
        return findByProjectId(projectId).map(ProjectAiSettings::getLlmProvider).orElse(null);
    }

    @Transactional
    public void setLlmProvider(Long projectId, String llmProvider) {
        ProjectAiSettings settings = getOrCreate(projectId);
        settings.setLlmProvider(llmProvider);
        repository.save(settings);
    }

    @Transactional(readOnly = true)
    public boolean hasBraveSearchApiKey(Long projectId) {
        return findByProjectId(projectId).map(ProjectAiSettings::hasBraveSearchApiKey).orElse(false);
    }

    @Transactional(readOnly = true)
    public byte[] getBraveSearchApiKeyEncrypted(Long projectId) {
        return findByProjectId(projectId).map(ProjectAiSettings::getBraveSearchApiKeyEncrypted).orElse(null);
    }

    @Transactional
    public void setBraveSearchApiKeyEncrypted(Long projectId, byte[] braveSearchApiKeyEncrypted) {
        ProjectAiSettings settings = getOrCreate(projectId);
        settings.setBraveSearchApiKeyEncrypted(braveSearchApiKeyEncrypted);
        repository.save(settings);
    }
}
