package com.letsblog.project.controller;

import com.letsblog.project.dto.ProjectCreateRequest;
import com.letsblog.project.dto.ProjectEnvironmentBindRequest;
import com.letsblog.project.dto.ProjectResponse;
import com.letsblog.project.dto.ProjectUpdateRequest;
import com.letsblog.project.dto.SyncEnvironmentRequest;
import com.letsblog.project.dto.UpdateMasterEnvironmentRequest;
import com.letsblog.project.dto.UpdateProjectGithubRepositoryRequest;
import com.letsblog.project.service.AdminAuthorizationService;
import com.letsblog.project.service.ProjectEnvironmentSyncService;
import com.letsblog.project.service.ProjectService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * プロジェクトのCRUD・環境紐付け・環境同期API(issue #577 stage2、legacy-apiから移設)。
 *
 * <p>legacy-apiのProjectControllerが併せ持っていた一括管理(bulk-management、カテゴリ/タグ/プラグイン/
 * テーマ/投稿の環境間比較・同期)・プロジェクトユーザー管理(/users)は、この移設の対象外
 * (BulkManagementService/TermComparisonService/PluginThemeComparisonService/PostComparisonService/
 * ProjectUserSyncServiceはproject-serviceへ移設していない)。legacy-api側に残る同名のProjectController
 * (該当エンドポイントのみへ縮小)が引き続き提供する。PR説明の既知の制限を参照。
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectEnvironmentSyncService projectEnvironmentSyncService;
    private final AdminAuthorizationService adminAuthorizationService;

    public ProjectController(
            ProjectService projectService,
            ProjectEnvironmentSyncService projectEnvironmentSyncService,
            AdminAuthorizationService adminAuthorizationService) {
        this.projectService = projectService;
        this.projectEnvironmentSyncService = projectEnvironmentSyncService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> create(@Valid @RequestBody ProjectCreateRequest request) {
        adminAuthorizationService.requireAdmin();
        ProjectResponse response = projectService.createProject(request.name(), request.slug());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * プロジェクト一覧。<b>操作者が所属するプロジェクトだけ</b>を返す(issue #830)。
     * admin は全件。以前は認可チェックが無く、認証済みなら誰でも全プロジェクトを列挙できた。
     */
    @GetMapping
    public List<ProjectResponse> list(
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder) {
        List<ProjectResponse> all = projectService.listProjects(sortBy, sortOrder);
        return adminAuthorizationService.accessibleProjectIds()
                .map(ids -> all.stream().filter(p -> ids.contains(p.id())).toList())
                .orElse(all);
    }

    @GetMapping("/{id}")
    public ProjectResponse get(@PathVariable Long id) {
        // 単一プロジェクトの参照は、そのプロジェクトのメンバー(またはadmin)に限定する(issue #830)。
        // 更新系が全てadmin限定である一方、参照が「認証済みなら誰でも」では、他人のプロジェクトの
        // 構成(GitHubリポジトリ・環境の紐付け等)が読めてしまう。
        adminAuthorizationService.requireProjectMemberOrAdmin(id);
        return projectService.getProject(id);
    }

    @PutMapping("/{id}")
    public ProjectResponse update(@PathVariable Long id, @Valid @RequestBody ProjectUpdateRequest request) {
        adminAuthorizationService.requireAdmin();
        return projectService.updateProject(id, request.name());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        projectService.deleteProject(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/environments")
    public ProjectResponse bindEnvironment(
            @PathVariable Long id, @Valid @RequestBody ProjectEnvironmentBindRequest request) {
        adminAuthorizationService.requireAdmin();
        return projectService.bindEnvironment(id, request.environment(), request.siteId());
    }

    @DeleteMapping("/{id}/environments/{environment}")
    public ProjectResponse unbindEnvironment(@PathVariable Long id, @PathVariable String environment) {
        adminAuthorizationService.requireAdmin();
        return projectService.unbindEnvironment(id, environment);
    }

    @PutMapping("/{id}/master-environment")
    public ProjectResponse updateMasterEnvironment(
            @PathVariable Long id, @Valid @RequestBody UpdateMasterEnvironmentRequest request) {
        adminAuthorizationService.requireAdmin();
        return projectService.updateMasterEnvironment(id, request.masterEnvironment());
    }

    @PutMapping("/{id}/github-repository")
    public ProjectResponse updateGithubRepository(
            @PathVariable Long id, @Valid @RequestBody UpdateProjectGithubRepositoryRequest request) {
        adminAuthorizationService.requireAdmin();
        return projectService.updateGithubRepository(id, request);
    }

    @PostMapping("/{id}/environments/sync")
    public ResponseEntity<Void> syncEnvironment(
            @PathVariable Long id, @Valid @RequestBody SyncEnvironmentRequest request) {
        adminAuthorizationService.requireAdmin();
        projectEnvironmentSyncService.sync(id, request.from(), request.to(), request.targets());
        return ResponseEntity.noContent().build();
    }
}
