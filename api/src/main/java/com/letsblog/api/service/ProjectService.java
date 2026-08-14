package com.letsblog.api.service;

import com.letsblog.api.aop.AuditLog;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.Site;
import com.letsblog.api.dto.ProjectResponse;
import com.letsblog.api.dto.UpdateImageGenerationPromptDefaultsRequest;
import com.letsblog.api.dto.UpdateProjectCssSelectorPrefixRequest;
import com.letsblog.api.dto.UpdateProjectGithubRepositoryRequest;
import com.letsblog.api.repository.ProjectRepository;
import com.letsblog.api.repository.SiteRepository;
import com.letsblog.api.dto.SiteResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
public class ProjectService {

    private static final Set<String> VALID_ENVIRONMENTS = Set.of("local", "test", "production");

    private final ProjectRepository projectRepository;
    private final SiteRepository siteRepository;
    private final BulkUploadStorageService bulkUploadStorageService;
    private final String globalDefaultNegativePrompt;
    private final String globalDefaultQualityPrompt;

    public ProjectService(
            ProjectRepository projectRepository,
            SiteRepository siteRepository,
            BulkUploadStorageService bulkUploadStorageService,
            @Value("${app.default-negative-prompt}") String globalDefaultNegativePrompt,
            @Value("${app.default-quality-prompt}") String globalDefaultQualityPrompt) {
        this.projectRepository = projectRepository;
        this.siteRepository = siteRepository;
        this.bulkUploadStorageService = bulkUploadStorageService;
        this.globalDefaultNegativePrompt = globalDefaultNegativePrompt;
        this.globalDefaultQualityPrompt = globalDefaultQualityPrompt;
    }

    @AuditLog(action = AuditLogAction.PROJECT_CREATED, resourceType = "PROJECT")
    @Transactional
    public ProjectResponse createProject(String name, String slug) {
        if (projectRepository.existsBySlug(slug)) {
            throw new IllegalArgumentException("slug '" + slug + "' は既に使用されています");
        }
        Project project = new Project();
        project.setName(name);
        project.setSlug(slug);
        return toResponse(projectRepository.save(project));
    }

    @Transactional(readOnly = true)
    public ProjectResponse getProject(Long projectId) {
        return toResponse(getProjectEntity(projectId));
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> listProjects(String sortBy, String sortOrder) {
        List<Project> projects = projectRepository.findAll();

        if (sortBy != null && !sortBy.isBlank()) {
            projects = sortProjects(projects, sortBy, sortOrder);
        } else {
            projects = sortProjects(projects, "createdAt", "desc");
        }

        return projects.stream().map(this::toResponse).toList();
    }

    private List<Project> sortProjects(List<Project> projects, String sortBy, String sortOrder) {
        boolean ascending = !"desc".equalsIgnoreCase(sortOrder);

        projects.sort((a, b) -> {
            int result = switch (sortBy) {
                case "name" -> a.getName().compareToIgnoreCase(b.getName());
                case "slug" -> a.getSlug().compareToIgnoreCase(b.getSlug());
                case "createdAt" -> a.getCreatedAt().compareTo(b.getCreatedAt());
                default -> 0;
            };
            return ascending ? result : -result;
        });

        return projects;
    }

    @AuditLog(action = AuditLogAction.PROJECT_UPDATED, resourceType = "PROJECT")
    @Transactional
    public ProjectResponse updateProject(Long projectId, String name) {
        Project project = getProjectEntity(projectId);
        project.setName(name);
        return toResponse(projectRepository.save(project));
    }

    @AuditLog(action = AuditLogAction.PROJECT_DELETED, resourceType = "PROJECT")
    @Transactional
    public void deleteProject(Long projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw new ProjectNotFoundException("id " + projectId + " のプロジェクトは登録されていません");
        }
        // project_users・bulk_operation_logsはDB側のON DELETE CASCADEで連動削除される
        projectRepository.deleteById(projectId);
        // 一括管理でアップロードされたzipファイルはDBのCASCADEでは消えないため、明示的に削除する
        bulkUploadStorageService.deleteAll(projectId);
    }

    @AuditLog(action = AuditLogAction.PROJECT_ENVIRONMENT_BOUND, resourceType = "PROJECT")
    @Transactional
    public ProjectResponse bindEnvironment(Long projectId, String environment, Long siteId) {
        Project project = getProjectEntity(projectId);
        requireValidEnvironment(environment);
        if (!siteRepository.existsById(siteId)) {
            throw new SiteNotFoundException("id " + siteId + " のサイトは登録されていません");
        }
        projectRepository.findByLocalSiteIdOrTestSiteIdOrProductionSiteId(siteId, siteId, siteId)
                .filter(p -> !p.getId().equals(projectId))
                .ifPresent(p -> {
                    throw new IllegalArgumentException(
                            "このサイトは既にプロジェクト「" + p.getName() + "」に紐付けられています");
                });

        switch (environment) {
            case "local" -> project.setLocalSiteId(siteId);
            case "test" -> project.setTestSiteId(siteId);
            case "production" -> project.setProductionSiteId(siteId);
            default -> throw new IllegalArgumentException("environment は local/test/production のいずれかを指定してください");
        }
        return toResponse(projectRepository.save(project));
    }

    @AuditLog(action = AuditLogAction.PROJECT_ENVIRONMENT_UNBOUND, resourceType = "PROJECT")
    @Transactional
    public ProjectResponse unbindEnvironment(Long projectId, String environment) {
        Project project = getProjectEntity(projectId);
        requireValidEnvironment(environment);
        switch (environment) {
            case "local" -> project.setLocalSiteId(null);
            case "test" -> project.setTestSiteId(null);
            case "production" -> project.setProductionSiteId(null);
            default -> throw new IllegalArgumentException("environment は local/test/production のいずれかを指定してください");
        }
        return toResponse(projectRepository.save(project));
    }

    @AuditLog(action = AuditLogAction.PROJECT_UPDATED, resourceType = "PROJECT")
    @Transactional
    public ProjectResponse updateMasterEnvironment(Long projectId, String masterEnvironment) {
        if (!Set.of("test", "production").contains(masterEnvironment)) {
            throw new IllegalArgumentException("マスター環境はtest/productionのいずれかを指定してください");
        }
        Project project = getProjectEntity(projectId);
        project.setMasterEnvironment(masterEnvironment);
        return toResponse(projectRepository.save(project));
    }

    /**
     * 指定サイトが所属するプロジェクトのIDを返す(いずれの環境にも紐付いていなければnull)。
     * カスタムタグのプロジェクトスコープ判定(投稿レンダリング時)に使う。
     */
    @Transactional(readOnly = true)
    public Long findProjectIdBySiteId(Long siteId) {
        return projectRepository.findByLocalSiteIdOrTestSiteIdOrProductionSiteId(siteId, siteId, siteId)
                .map(Project::getId)
                .orElse(null);
    }

    @AuditLog(action = AuditLogAction.PROJECT_UPDATED, resourceType = "PROJECT")
    @Transactional
    public ProjectResponse updateGithubRepository(Long projectId, UpdateProjectGithubRepositoryRequest request) {
        Project project = getProjectEntity(projectId);
        String repo = request.githubRepository() == null || request.githubRepository().isBlank()
                ? null
                : request.githubRepository();
        project.setGithubRepository(repo);
        return toResponse(projectRepository.save(project));
    }

    @AuditLog(action = AuditLogAction.PROJECT_UPDATED, resourceType = "PROJECT")
    @Transactional
    public ProjectResponse updateCssSelectorPrefix(Long projectId, UpdateProjectCssSelectorPrefixRequest request) {
        Project project = getProjectEntity(projectId);
        String prefix = request.cssSelectorPrefix() == null || request.cssSelectorPrefix().isBlank()
                ? null
                : request.cssSelectorPrefix();
        project.setCssSelectorPrefix(prefix);
        return toResponse(projectRepository.save(project));
    }

    /**
     * カスタムタグCSSのセレクタに付与するプリフィックスを解決する。未設定時はプロジェクトのslugを使う(issue #298)。
     */
    public String resolveCssSelectorPrefix(Project project) {
        return project.getCssSelectorPrefix() == null || project.getCssSelectorPrefix().isBlank()
                ? project.getSlug()
                : project.getCssSelectorPrefix();
    }

    @AuditLog(action = AuditLogAction.PROJECT_UPDATED, resourceType = "PROJECT")
    @Transactional
    public ProjectResponse updateImageGenerationPromptDefaults(
            Long projectId, UpdateImageGenerationPromptDefaultsRequest request) {
        Project project = getProjectEntity(projectId);
        project.setDefaultNegativePrompt(blankToNull(request.defaultNegativePrompt()));
        project.setDefaultQualityPrompt(blankToNull(request.defaultQualityPrompt()));
        return toResponse(projectRepository.save(project));
    }

    /**
     * 画像生成時のnegative promptを解決する。プロジェクト未設定時・projectId未指定時はアプリ全体の
     * デフォルトにフォールバックする(issue #293)。
     */
    public String resolveDefaultNegativePrompt(Long projectId) {
        if (projectId == null) {
            return globalDefaultNegativePrompt;
        }
        String projectValue = getProjectEntity(projectId).getDefaultNegativePrompt();
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
        String projectValue = getProjectEntity(projectId).getDefaultQualityPrompt();
        return projectValue == null || projectValue.isBlank() ? globalDefaultQualityPrompt : projectValue;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void requireValidEnvironment(String environment) {
        if (!VALID_ENVIRONMENTS.contains(environment)) {
            throw new IllegalArgumentException("environment は local/test/production のいずれかを指定してください");
        }
    }

    public Project getProjectEntity(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException("id " + projectId + " のプロジェクトは登録されていません"));
    }

    /**
     * プロジェクトのマスター環境(test/production)に紐づくサイトを解決する。未紐付けの場合はnullを返す。
     * テーマCSS取得(ArticlePreviewService)・既存カテゴリ一覧取得(ArticlePlanService)など、
     * 「複数環境のうちどれを基準にするか」を要する機能から共通で利用する。
     */
    public Site resolveMasterSite(Project project) {
        Long siteId = switch (project.getMasterEnvironment()) {
            case "test" -> project.getTestSiteId();
            case "production" -> project.getProductionSiteId();
            default -> null;
        };
        return siteId == null ? null : siteRepository.findById(siteId).orElse(null);
    }

    private ProjectResponse toResponse(Project project) {
        return ProjectResponse.from(
                project,
                resolveSite(project.getLocalSiteId()),
                resolveSite(project.getTestSiteId()),
                resolveSite(project.getProductionSiteId()));
    }

    private SiteResponse resolveSite(Long siteId) {
        if (siteId == null) {
            return null;
        }
        return siteRepository.findById(siteId).map(SiteResponse::from).orElse(null);
    }
}
