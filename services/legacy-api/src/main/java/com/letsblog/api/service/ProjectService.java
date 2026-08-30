package com.letsblog.api.service;

import com.letsblog.api.aop.AuditLog;
import com.letsblog.api.client.ContentServiceClient;
import com.letsblog.api.client.ProjectServiceClient;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.ProjectImageSettings;
import com.letsblog.api.dto.ProjectResponse;
import com.letsblog.api.dto.SiteResponse;
import com.letsblog.api.dto.UpdateArticleImageResizeDefaultRequest;
import com.letsblog.api.dto.UpdateImageContentFilterSettingsRequest;
import com.letsblog.api.dto.UpdateImageGenerationPromptDefaultsRequest;
import com.letsblog.api.dto.UpdateImageGenerationSizeDefaultsRequest;
import com.letsblog.api.dto.UpdateProjectCssSelectorPrefixRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * プロジェクトの一部の設定(CSSセレクタプリフィックス・画像生成デフォルト設定)の読み書き。
 * プロジェクトのCRUD・環境紐付け・環境同期はproject-serviceへ移設した(issue #577 stage2)。
 * これらの設定は、project-serviceの{@code ProjectService}には無い依存
 * (content-serviceへの内部ブリッジ、legacy-apiローカルの{@code ProjectImageSettingsService})を伴うため、
 * legacy-api側に残った(#577の既知の制限、{@code ProjectController}のjavadoc参照)。
 *
 * <p>プロジェクトの基本情報(存在確認・masterEnvironment・環境ごとのsiteId・githubRepository等)自体は、
 * {@link ProjectServiceClient}経由でproject-serviceから取得する(issue #577 stage3。旧
 * {@code ProjectRepository}(ローカルJPA)は削除した。{@link ProjectApiKeyService}のGitHubトークン
 * 読み書きも、同じ{@link ProjectServiceClient}の専用ブリッジ({@code getGithubToken}/{@code setGithubToken})
 * 経由に切り替えた。分析/AI資格情報ドメインのためProjectApiKeyService自体は#577スコープ外)。
 */
@Service
public class ProjectService {

    private final ProjectServiceClient projectServiceClient;
    private final SiteService siteService;
    private final ProjectImageSettingsService projectImageSettingsService;
    private final ContentServiceClient contentServiceClient;
    private final String globalDefaultNegativePrompt;
    private final String globalDefaultQualityPrompt;
    private final int globalDefaultGeneratedImageWidth;
    private final int globalDefaultGeneratedImageHeight;
    private final int globalDefaultArticleImageLongEdgePx;
    private final boolean globalDefaultBlockSexualContent;
    private final boolean globalDefaultBlockViolentContent;
    private final boolean globalDefaultBlockDiscriminatoryContent;

    public ProjectService(
            ProjectServiceClient projectServiceClient,
            SiteService siteService,
            ProjectImageSettingsService projectImageSettingsService,
            ContentServiceClient contentServiceClient,
            @Value("${app.default-negative-prompt}") String globalDefaultNegativePrompt,
            @Value("${app.default-quality-prompt}") String globalDefaultQualityPrompt,
            @Value("${app.default-generated-image-width}") int globalDefaultGeneratedImageWidth,
            @Value("${app.default-generated-image-height}") int globalDefaultGeneratedImageHeight,
            @Value("${app.default-article-image-long-edge-px}") int globalDefaultArticleImageLongEdgePx,
            @Value("${app.default-block-sexual-content}") boolean globalDefaultBlockSexualContent,
            @Value("${app.default-block-violent-content}") boolean globalDefaultBlockViolentContent,
            @Value("${app.default-block-discriminatory-content}") boolean globalDefaultBlockDiscriminatoryContent) {
        this.projectServiceClient = projectServiceClient;
        this.siteService = siteService;
        this.projectImageSettingsService = projectImageSettingsService;
        this.contentServiceClient = contentServiceClient;
        this.globalDefaultNegativePrompt = globalDefaultNegativePrompt;
        this.globalDefaultQualityPrompt = globalDefaultQualityPrompt;
        this.globalDefaultGeneratedImageWidth = globalDefaultGeneratedImageWidth;
        this.globalDefaultGeneratedImageHeight = globalDefaultGeneratedImageHeight;
        this.globalDefaultArticleImageLongEdgePx = globalDefaultArticleImageLongEdgePx;
        this.globalDefaultBlockSexualContent = globalDefaultBlockSexualContent;
        this.globalDefaultBlockViolentContent = globalDefaultBlockViolentContent;
        this.globalDefaultBlockDiscriminatoryContent = globalDefaultBlockDiscriminatoryContent;
    }

    /** {@code ProjectController#applyBulkOperation}が使う、マスター環境の判定用。 */
    @Transactional(readOnly = true)
    public ProjectResponse getProject(Long projectId) {
        return toResponse(getProjectEntity(projectId));
    }

    @AuditLog(action = AuditLogAction.PROJECT_UPDATED, resourceType = "PROJECT")
    @Transactional
    public ProjectResponse updateCssSelectorPrefix(Long projectId, UpdateProjectCssSelectorPrefixRequest request) {
        Project project = getProjectEntity(projectId);
        String prefix = blankToNull(request.cssSelectorPrefix());
        // project_content_settingsの所有権はcontent-serviceへ移った(issue #576)ため、内部ブリッジ
        // (ContentServiceClient)経由で更新する。
        contentServiceClient.updateCssSelectorPrefix(projectId, prefix);
        return toResponse(project);
    }

    // resolveCssSelectorPrefix(カスタムタグCSSのセレクタプリフィックス解決、issue #298)は、唯一の
    // 呼び出し元だったCustomTagService/RenderedContentWrapperServiceがcontent-serviceへ移設された
    // ため削除した(issue #576)。同等のロジックはcontent-service側の
    // ProjectContentSettingsService#resolveCssSelectorPrefixへ移設し、未設定時のフォールバック
    // (プロジェクトのslug)はContentBridgeController#projectSlug経由でこちらへ問い合わせる。

    @AuditLog(action = AuditLogAction.PROJECT_UPDATED, resourceType = "PROJECT")
    @Transactional
    public ProjectResponse updateImageGenerationPromptDefaults(
            Long projectId, UpdateImageGenerationPromptDefaultsRequest request) {
        Project project = getProjectEntity(projectId);
        projectImageSettingsService.updateImageGenerationPromptDefaults(
                projectId, blankToNull(request.defaultNegativePrompt()), blankToNull(request.defaultQualityPrompt()));
        return toResponse(project);
    }

    /**
     * 画像生成時のnegative promptを解決する。プロジェクト未設定時・projectId未指定時はアプリ全体の
     * デフォルトにフォールバックする(issue #293)。
     */
    public String resolveDefaultNegativePrompt(Long projectId) {
        if (projectId == null) {
            return globalDefaultNegativePrompt;
        }
        getProjectEntity(projectId);
        String projectValue = projectImageSettingsService.findByProjectId(projectId)
                .map(ProjectImageSettings::getDefaultNegativePrompt).orElse(null);
        return projectValue == null || projectValue.isBlank() ? globalDefaultNegativePrompt : projectValue;
    }

    /**
     * 画像生成時に本文プロンプトへ追加する画質プロンプトを解決する(issue #293)。
     * 呼び出し元でprompt末尾へカンマ区切りで追加することを想定する。
     */
    public String resolveDefaultQualityPrompt(Long projectId) {
        if (projectId == null) {
            return globalDefaultQualityPrompt;
        }
        getProjectEntity(projectId);
        String projectValue = projectImageSettingsService.findByProjectId(projectId)
                .map(ProjectImageSettings::getDefaultQualityPrompt).orElse(null);
        return projectValue == null || projectValue.isBlank() ? globalDefaultQualityPrompt : projectValue;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @AuditLog(action = AuditLogAction.PROJECT_UPDATED, resourceType = "PROJECT")
    @Transactional
    public ProjectResponse updateImageGenerationSizeDefaults(
            Long projectId, UpdateImageGenerationSizeDefaultsRequest request) {
        Project project = getProjectEntity(projectId);
        projectImageSettingsService.updateImageGenerationSizeDefaults(
                projectId, request.defaultGeneratedImageWidth(), request.defaultGeneratedImageHeight());
        return toResponse(project);
    }

    /**
     * 画像生成時のデフォルト幅を解決する。プロジェクト未設定時・projectId未指定時はアプリ全体の
     * デフォルト(既定1920)にフォールバックする(issue #292)。
     */
    public int resolveDefaultGeneratedImageWidth(Long projectId) {
        if (projectId == null) {
            return globalDefaultGeneratedImageWidth;
        }
        getProjectEntity(projectId);
        Integer projectValue = projectImageSettingsService.findByProjectId(projectId)
                .map(ProjectImageSettings::getDefaultGeneratedImageWidth).orElse(null);
        return projectValue == null ? globalDefaultGeneratedImageWidth : projectValue;
    }

    /**
     * 画像生成時のデフォルト高さを解決する。プロジェクト未設定時・projectId未指定時はアプリ全体の
     * デフォルト(既定1080)にフォールバックする(issue #292)。
     */
    public int resolveDefaultGeneratedImageHeight(Long projectId) {
        if (projectId == null) {
            return globalDefaultGeneratedImageHeight;
        }
        getProjectEntity(projectId);
        Integer projectValue = projectImageSettingsService.findByProjectId(projectId)
                .map(ProjectImageSettings::getDefaultGeneratedImageHeight).orElse(null);
        return projectValue == null ? globalDefaultGeneratedImageHeight : projectValue;
    }

    @AuditLog(action = AuditLogAction.PROJECT_UPDATED, resourceType = "PROJECT")
    @Transactional
    public ProjectResponse updateArticleImageResizeDefault(Long projectId, UpdateArticleImageResizeDefaultRequest request) {
        Project project = getProjectEntity(projectId);
        projectImageSettingsService.updateArticleImageResizeDefault(projectId, request.defaultArticleImageLongEdgePx());
        return toResponse(project);
    }

    /**
     * 記事投稿時に画像をリサイズする長編の目標pxを解決する。プロジェクト未設定時・projectId未指定時は
     * アプリ全体のデフォルト(既定1300)にフォールバックする(issue #291)。
     */
    public int resolveArticleImageLongEdgePx(Long projectId) {
        if (projectId == null) {
            return globalDefaultArticleImageLongEdgePx;
        }
        getProjectEntity(projectId);
        Integer projectValue = projectImageSettingsService.findByProjectId(projectId)
                .map(ProjectImageSettings::getDefaultArticleImageLongEdgePx).orElse(null);
        return projectValue == null ? globalDefaultArticleImageLongEdgePx : projectValue;
    }

    @AuditLog(action = AuditLogAction.PROJECT_UPDATED, resourceType = "PROJECT")
    @Transactional
    public ProjectResponse updateImageContentFilterSettings(
            Long projectId, UpdateImageContentFilterSettingsRequest request) {
        Project project = getProjectEntity(projectId);
        projectImageSettingsService.updateImageContentFilterSettings(
                projectId, request.blockSexualContent(), request.blockViolentContent(),
                request.blockDiscriminatoryContent());
        return toResponse(project);
    }

    /**
     * 画像生成時に性的コンテンツをブロックするかどうかを解決する。プロジェクト未設定時・projectId未指定時は
     * アプリ全体のデフォルト(既定true)にフォールバックする(issue #532)。
     */
    public boolean resolveBlockSexualContent(Long projectId) {
        if (projectId == null) {
            return globalDefaultBlockSexualContent;
        }
        getProjectEntity(projectId);
        Boolean projectValue = projectImageSettingsService.findByProjectId(projectId)
                .map(ProjectImageSettings::getBlockSexualContent).orElse(null);
        return projectValue == null ? globalDefaultBlockSexualContent : projectValue;
    }

    /**
     * 画像生成時に暴力的コンテンツをブロックするかどうかを解決する(issue #532)。
     */
    public boolean resolveBlockViolentContent(Long projectId) {
        if (projectId == null) {
            return globalDefaultBlockViolentContent;
        }
        getProjectEntity(projectId);
        Boolean projectValue = projectImageSettingsService.findByProjectId(projectId)
                .map(ProjectImageSettings::getBlockViolentContent).orElse(null);
        return projectValue == null ? globalDefaultBlockViolentContent : projectValue;
    }

    /**
     * 画像生成時に差別的表現をブロックするかどうかを解決する(issue #532)。
     */
    public boolean resolveBlockDiscriminatoryContent(Long projectId) {
        if (projectId == null) {
            return globalDefaultBlockDiscriminatoryContent;
        }
        getProjectEntity(projectId);
        Boolean projectValue = projectImageSettingsService.findByProjectId(projectId)
                .map(ProjectImageSettings::getBlockDiscriminatoryContent).orElse(null);
        return projectValue == null ? globalDefaultBlockDiscriminatoryContent : projectValue;
    }

    /**
     * 指定サイトが所属するプロジェクトのIDを返す(いずれの環境にも紐付いていなければnull)。
     * カスタムタグのプロジェクトスコープ判定(投稿レンダリング時)に使う。
     */
    public Long findProjectIdBySiteId(Long siteId) {
        return projectServiceClient.findProjectIdBySiteId(siteId);
    }

    /** {@link ProjectNotFoundException}を投げる、project-service経由のプロジェクト存在確認+取得。 */
    public Project getProjectEntity(Long projectId) {
        return toProject(projectServiceClient.getProject(projectId));
    }

    private ProjectResponse toResponse(Project project) {
        ProjectImageSettings imageSettings =
                projectImageSettingsService.findByProjectId(project.getId()).orElse(null);
        // project_content_settingsの所有権はcontent-serviceへ移った(issue #576)ため、内部ブリッジ
        // (ContentServiceClient)経由で取得する。
        String cssSelectorPrefix = contentServiceClient.getCssSelectorPrefix(project.getId());
        return ProjectResponse.from(
                project,
                resolveSite(project.getLocalSiteId()),
                resolveSite(project.getTestSiteId()),
                resolveSite(project.getProductionSiteId()),
                imageSettings,
                cssSelectorPrefix);
    }

    private SiteResponse resolveSite(Long siteId) {
        if (siteId == null) {
            return null;
        }
        return siteService.getById(siteId)
                .map(site -> SiteResponse.from(site, null, siteService.isSshConfigured(site)))
                .orElse(null);
    }

    private Project toProject(ProjectServiceClient.ProjectBridge bridge) {
        Project project = new Project();
        project.setId(bridge.id());
        project.setName(bridge.name());
        project.setSlug(bridge.slug());
        project.setMasterEnvironment(bridge.masterEnvironment());
        project.setLocalSiteId(bridge.localSiteId());
        project.setTestSiteId(bridge.testSiteId());
        project.setProductionSiteId(bridge.productionSiteId());
        project.setGithubRepository(bridge.githubRepository());
        project.setCreatedAt(bridge.createdAt());
        project.setUpdatedAt(bridge.updatedAt());
        return project;
    }
}
