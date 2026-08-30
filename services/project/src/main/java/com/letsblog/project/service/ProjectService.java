package com.letsblog.project.service;

import com.letsblog.project.aop.AuditLog;
import com.letsblog.project.domain.AuditLogAction;
import com.letsblog.project.domain.Project;
import com.letsblog.project.domain.Site;
import com.letsblog.project.dto.ProjectResponse;
import com.letsblog.project.dto.SiteResponse;
import com.letsblog.project.dto.UpdateProjectGithubRepositoryRequest;
import com.letsblog.project.messaging.DomainEventPublisher;
import com.letsblog.project.repository.ProjectRepository;
import com.letsblog.project.repository.SiteRepository;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * プロジェクトのCRUD・環境(local/test/production)紐付けを管理する(issue #577 stage2、legacy-apiから移設)。
 *
 * <p>legacy-api版が内部ブリッジ経由で合成していた他サービス所有の設定
 * (project_ai_settings/project_image_settings/analytics_credentials/project_content_settings、
 * いずれもissue #571のC2で分割済みで、project-serviceの所有ドメインではない)は、本サービスの
 * {@link ProjectResponse}には含めない(PR説明の既知の制限を参照。Web側は各サービスから個別に取得する)。
 */
@Service
public class ProjectService {

    private static final Set<String> VALID_ENVIRONMENTS = Set.of("local", "test", "production");

    private final ProjectRepository projectRepository;
    private final SiteRepository siteRepository;
    private final SiteService siteService;
    private final DomainEventPublisher domainEventPublisher;

    public ProjectService(
            ProjectRepository projectRepository, SiteRepository siteRepository, SiteService siteService,
            DomainEventPublisher domainEventPublisher) {
        this.projectRepository = projectRepository;
        this.siteRepository = siteRepository;
        this.siteService = siteService;
        this.domainEventPublisher = domainEventPublisher;
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

    /**
     * project_users・bulk_operation_logsテーブルはまだlegacy-apiに残るため(issue #577 stage2の
     * スコープ外)、これらのON DELETE CASCADEはこちらのスキーマには存在しない。project.deletedイベント
     * (issue #580)を発行するが、legacy-api側に現状これを購読して関連データを整理する処理は無い
     * (ADR-0004のクロススキーマFK禁止に起因する、#577固有ではない既知のギャップ。
     * EventExchanges#PROJECT_DELETED_ROUTING_KEYのjavadoc参照)。
     */
    @AuditLog(action = AuditLogAction.PROJECT_DELETED, resourceType = "PROJECT")
    @Transactional
    public void deleteProject(Long projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw new ProjectNotFoundException("id " + projectId + " のプロジェクトは登録されていません");
        }
        projectRepository.deleteById(projectId);
        domainEventPublisher.publishProjectDeleted(projectId);
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
     * legacy-apiの{@code ProjectApiKeyService}が使う、GitHubトークン(暗号化済みバイト列)の取得。
     * 復号はしない(暗号鍵は全サービス共通のAPP_ENCRYPTION_KEYで、呼び出し元が復号する。issue #577 stage3)。
     */
    @Transactional(readOnly = true)
    public byte[] getGithubTokenEncrypted(Long projectId) {
        return getProjectEntity(projectId).getGithubTokenEncrypted();
    }

    @Transactional
    public void setGithubTokenEncrypted(Long projectId, byte[] encryptedToken) {
        Project project = getProjectEntity(projectId);
        project.setGithubTokenEncrypted(encryptedToken);
        projectRepository.save(project);
    }

    /**
     * プロジェクトのマスター環境(test/production)に紐づくサイトを解決する。未紐付けの場合はnullを返す。
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
        return siteRepository.findById(siteId)
                .map(site -> SiteResponse.from(site, null, siteService.isSshConfigured(site)))
                .orElse(null);
    }
}
