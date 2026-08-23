package com.letsblog.api.service;

import com.letsblog.api.domain.ProjectContentSettings;
import com.letsblog.api.repository.ProjectContentSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * プロジェクト単位のコンテンツ(content)関連設定(project_content_settings)の読み書きを扱う(issue #571)。
 * projects god-tableの分割で切り出された設定テーブルで、行は初回書き込み時に遅延作成する
 * (未設定のプロジェクトに空行を作らないため)。
 */
@Service
public class ProjectContentSettingsService {

    private final ProjectContentSettingsRepository repository;

    public ProjectContentSettingsService(ProjectContentSettingsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<ProjectContentSettings> findByProjectId(Long projectId) {
        return repository.findByProjectId(projectId);
    }

    @Transactional
    public ProjectContentSettings getOrCreate(Long projectId) {
        return repository.findByProjectId(projectId)
                .orElseGet(() -> repository.save(new ProjectContentSettings(projectId)));
    }

    @Transactional(readOnly = true)
    public String getCssSelectorPrefix(Long projectId) {
        return findByProjectId(projectId).map(ProjectContentSettings::getCssSelectorPrefix).orElse(null);
    }

    @Transactional
    public ProjectContentSettings updateCssSelectorPrefix(Long projectId, String cssSelectorPrefix) {
        ProjectContentSettings settings = getOrCreate(projectId);
        settings.setCssSelectorPrefix(cssSelectorPrefix);
        return repository.save(settings);
    }
}
