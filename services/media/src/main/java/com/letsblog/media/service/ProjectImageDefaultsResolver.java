package com.letsblog.media.service;

import com.letsblog.media.client.ProjectServiceClient;
import com.letsblog.media.domain.ProjectImageSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 画像生成に使う設定値を「プロジェクト単位の上書き → アプリ全体の既定値」の順で解決する
 * (issue #583でlegacy-apiの{@code ProjectService}から移設)。
 *
 * <p>{@code project_image_settings}の所有権が{@code lbs_media}へ移った(#583)ため、
 * 上書き値は{@link ProjectImageSettingsService}がローカルに読む。
 * 既定値は{@code app.default-*}(環境変数で上書き可)である。
 *
 * <p>{@code projectId}が{@code null}(VSCode拡張がプロジェクト未選択のまま呼ぶ場合)は
 * プロジェクトの存在確認を行わず、そのまま全体既定値を返す。移設前と同じ挙動。
 */
@Service
public class ProjectImageDefaultsResolver {

    private final ProjectImageSettingsService projectImageSettingsService;
    private final ProjectServiceClient projectServiceClient;
    private final String globalDefaultNegativePrompt;
    private final String globalDefaultQualityPrompt;
    private final int globalDefaultGeneratedImageWidth;
    private final int globalDefaultGeneratedImageHeight;
    private final int globalDefaultArticleImageLongEdgePx;
    private final boolean globalDefaultBlockSexualContent;
    private final boolean globalDefaultBlockViolentContent;
    private final boolean globalDefaultBlockDiscriminatoryContent;

    public ProjectImageDefaultsResolver(
            ProjectImageSettingsService projectImageSettingsService,
            ProjectServiceClient projectServiceClient,
            @Value("${app.default-negative-prompt}") String globalDefaultNegativePrompt,
            @Value("${app.default-quality-prompt}") String globalDefaultQualityPrompt,
            @Value("${app.default-generated-image-width}") int globalDefaultGeneratedImageWidth,
            @Value("${app.default-generated-image-height}") int globalDefaultGeneratedImageHeight,
            @Value("${app.default-article-image-long-edge-px}") int globalDefaultArticleImageLongEdgePx,
            @Value("${app.default-block-sexual-content}") boolean globalDefaultBlockSexualContent,
            @Value("${app.default-block-violent-content}") boolean globalDefaultBlockViolentContent,
            @Value("${app.default-block-discriminatory-content}") boolean globalDefaultBlockDiscriminatoryContent) {
        this.projectImageSettingsService = projectImageSettingsService;
        this.projectServiceClient = projectServiceClient;
        this.globalDefaultNegativePrompt = globalDefaultNegativePrompt;
        this.globalDefaultQualityPrompt = globalDefaultQualityPrompt;
        this.globalDefaultGeneratedImageWidth = globalDefaultGeneratedImageWidth;
        this.globalDefaultGeneratedImageHeight = globalDefaultGeneratedImageHeight;
        this.globalDefaultArticleImageLongEdgePx = globalDefaultArticleImageLongEdgePx;
        this.globalDefaultBlockSexualContent = globalDefaultBlockSexualContent;
        this.globalDefaultBlockViolentContent = globalDefaultBlockViolentContent;
        this.globalDefaultBlockDiscriminatoryContent = globalDefaultBlockDiscriminatoryContent;
    }

    public String resolveDefaultNegativePrompt(Long projectId) {
        return resolveString(projectId, ProjectImageSettings::getDefaultNegativePrompt, globalDefaultNegativePrompt);
    }

    public String resolveDefaultQualityPrompt(Long projectId) {
        return resolveString(projectId, ProjectImageSettings::getDefaultQualityPrompt, globalDefaultQualityPrompt);
    }

    public int resolveDefaultGeneratedImageWidth(Long projectId) {
        return resolveInt(
                projectId, ProjectImageSettings::getDefaultGeneratedImageWidth, globalDefaultGeneratedImageWidth);
    }

    public int resolveDefaultGeneratedImageHeight(Long projectId) {
        return resolveInt(
                projectId, ProjectImageSettings::getDefaultGeneratedImageHeight, globalDefaultGeneratedImageHeight);
    }

    public int resolveArticleImageLongEdgePx(Long projectId) {
        return resolveInt(
                projectId, ProjectImageSettings::getDefaultArticleImageLongEdgePx, globalDefaultArticleImageLongEdgePx);
    }

    public boolean resolveBlockSexualContent(Long projectId) {
        return resolveBoolean(
                projectId, ProjectImageSettings::getBlockSexualContent, globalDefaultBlockSexualContent);
    }

    public boolean resolveBlockViolentContent(Long projectId) {
        return resolveBoolean(
                projectId, ProjectImageSettings::getBlockViolentContent, globalDefaultBlockViolentContent);
    }

    public boolean resolveBlockDiscriminatoryContent(Long projectId) {
        return resolveBoolean(
                projectId, ProjectImageSettings::getBlockDiscriminatoryContent, globalDefaultBlockDiscriminatoryContent);
    }

    private interface Extractor<T> {
        T extract(ProjectImageSettings settings);
    }

    private <T> T resolveOverride(Long projectId, Extractor<T> extractor) {
        projectServiceClient.requireProjectExists(projectId);
        return projectImageSettingsService.findByProjectId(projectId).map(extractor::extract).orElse(null);
    }

    /**
     * 文字列だけは<b>空文字も未設定として扱う</b>(移設前の{@code ProjectService}と同じ挙動)。
     * 数値・真偽値は{@code null}のみを未設定として扱う。
     */
    private String resolveString(Long projectId, Extractor<String> extractor, String globalDefault) {
        if (projectId == null) {
            return globalDefault;
        }
        String value = resolveOverride(projectId, extractor);
        return value == null || value.isBlank() ? globalDefault : value;
    }

    private int resolveInt(Long projectId, Extractor<Integer> extractor, int globalDefault) {
        if (projectId == null) {
            return globalDefault;
        }
        Integer value = resolveOverride(projectId, extractor);
        return value != null ? value : globalDefault;
    }

    private boolean resolveBoolean(Long projectId, Extractor<Boolean> extractor, boolean globalDefault) {
        if (projectId == null) {
            return globalDefault;
        }
        Boolean value = resolveOverride(projectId, extractor);
        return value != null ? value : globalDefault;
    }
}
