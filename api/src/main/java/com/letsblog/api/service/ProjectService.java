package com.letsblog.api.service;

import com.letsblog.api.aop.AuditLog;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.domain.Project;
import com.letsblog.api.dto.ProjectResponse;
import com.letsblog.api.dto.UpdateProjectGithubRepositoryRequest;
import com.letsblog.api.repository.ProjectRepository;
import com.letsblog.api.repository.SiteRepository;
import com.letsblog.api.dto.SiteResponse;
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

    public ProjectService(
            ProjectRepository projectRepository,
            SiteRepository siteRepository,
            BulkUploadStorageService bulkUploadStorageService) {
        this.projectRepository = projectRepository;
        this.siteRepository = siteRepository;
        this.bulkUploadStorageService = bulkUploadStorageService;
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
    public List<ProjectResponse> listProjects() {
        return projectRepository.findAllByOrderByCreatedAtDesc().stream().map(this::toResponse).toList();
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

    private void requireValidEnvironment(String environment) {
        if (!VALID_ENVIRONMENTS.contains(environment)) {
            throw new IllegalArgumentException("environment は local/test/production のいずれかを指定してください");
        }
    }

    public Project getProjectEntity(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException("id " + projectId + " のプロジェクトは登録されていません"));
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
