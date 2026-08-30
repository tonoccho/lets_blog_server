package com.letsblog.content.service;

import com.letsblog.content.client.LegacyApiBridgeClient;
import com.letsblog.content.domain.ProjectContentSettings;
import com.letsblog.content.repository.ProjectContentSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * プロジェクト単位のコンテンツ(content)関連設定(project_content_settings)の読み書きを扱う(issue #571、
 * #576でcontent-serviceへ移管)。行は初回書き込み時に遅延作成する(未設定のプロジェクトに空行を作らないため)。
 */
@Service
public class ProjectContentSettingsService {

    private final ProjectContentSettingsRepository repository;
    private final LegacyApiBridgeClient legacyApiBridgeClient;
    private final CurrentActorService currentActorService;

    public ProjectContentSettingsService(
            ProjectContentSettingsRepository repository,
            LegacyApiBridgeClient legacyApiBridgeClient,
            CurrentActorService currentActorService) {
        this.repository = repository;
        this.legacyApiBridgeClient = legacyApiBridgeClient;
        this.currentActorService = currentActorService;
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

    /**
     * カスタムタグCSSのセレクタに付与するプリフィックスを解決する。未設定時はプロジェクトのslugを使う
     * (issue #298、legacy-apiのProjectService#resolveCssSelectorPrefixと同じ方針)。
     * project_content_settingsは本サービスが所有するため自身のリポジトリで解決するが、フォールバック先の
     * プロジェクトslugはproject-serviceが未抽出のままlegacy-apiに残っている(ADR-0004)ため、
     * 未設定の場合のみ内部ブリッジ({@link LegacyApiBridgeClient#resolveProjectSlug}）で問い合わせる。
     */
    @Transactional(readOnly = true)
    public String resolveCssSelectorPrefix(Long projectId) {
        String prefix = getCssSelectorPrefix(projectId);
        if (prefix != null && !prefix.isBlank()) {
            return prefix;
        }
        return legacyApiBridgeClient.resolveProjectSlug(projectId, currentActorService.getAuthorizationHeader());
    }
}
