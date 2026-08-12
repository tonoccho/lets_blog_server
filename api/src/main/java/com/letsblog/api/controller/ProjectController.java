package com.letsblog.api.controller;

import com.letsblog.api.dto.AddProjectUserRequest;
import com.letsblog.api.dto.ApplyToEnvironmentRequest;
import com.letsblog.api.dto.BulkOperationLogResponse;
import com.letsblog.api.dto.DeleteSlugRequest;
import com.letsblog.api.dto.EditTermRequest;
import com.letsblog.api.dto.ProjectCreateRequest;
import com.letsblog.api.dto.ProjectEnvironmentBindRequest;
import com.letsblog.api.dto.ProjectResponse;
import com.letsblog.api.dto.ProjectUpdateRequest;
import com.letsblog.api.dto.PostComparisonPage;
import com.letsblog.api.dto.ProjectUserResponse;
import com.letsblog.api.dto.ReconcileStateRequest;
import com.letsblog.api.dto.StatusComparisonPage;
import com.letsblog.api.dto.SyncEnvironmentRequest;
import com.letsblog.api.dto.TermComparisonPage;
import com.letsblog.api.dto.TermNameRequest;
import com.letsblog.api.dto.UpdateMasterEnvironmentRequest;
import com.letsblog.api.dto.UpdatePostStatusRequest;
import com.letsblog.api.dto.UpdateProjectCssSelectorPrefixRequest;
import com.letsblog.api.dto.UpdateProjectGithubRepositoryRequest;
import com.letsblog.api.dto.UpdateProjectUserRequest;
import com.letsblog.api.ai.GeneratedImageStorageService;
import com.letsblog.api.domain.BulkOperationLog;
import com.letsblog.api.domain.BulkOperationType;
import com.letsblog.api.domain.GeneratedImage;
import com.letsblog.api.repository.GeneratedImageRepository;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.BulkManagementService;
import com.letsblog.api.service.CurrentActorService;
import com.letsblog.api.service.GeneratedImageNotFoundException;
import com.letsblog.api.service.PluginThemeComparisonService;
import com.letsblog.api.service.PostComparisonService;
import com.letsblog.api.service.ProjectEnvironmentSyncService;
import com.letsblog.api.service.ProjectService;
import com.letsblog.api.service.ProjectUserSyncService;
import com.letsblog.api.service.TermComparisonService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectUserSyncService projectUserSyncService;
    private final ProjectEnvironmentSyncService projectEnvironmentSyncService;
    private final BulkManagementService bulkManagementService;
    private final TermComparisonService termComparisonService;
    private final PluginThemeComparisonService pluginThemeComparisonService;
    private final PostComparisonService postComparisonService;
    private final AdminAuthorizationService adminAuthorizationService;
    private final CurrentActorService currentActorService;
    private final GeneratedImageRepository generatedImageRepository;
    private final GeneratedImageStorageService generatedImageStorageService;

    public ProjectController(
            ProjectService projectService,
            ProjectUserSyncService projectUserSyncService,
            ProjectEnvironmentSyncService projectEnvironmentSyncService,
            BulkManagementService bulkManagementService,
            TermComparisonService termComparisonService,
            PluginThemeComparisonService pluginThemeComparisonService,
            PostComparisonService postComparisonService,
            AdminAuthorizationService adminAuthorizationService,
            CurrentActorService currentActorService,
            GeneratedImageRepository generatedImageRepository,
            GeneratedImageStorageService generatedImageStorageService) {
        this.projectService = projectService;
        this.projectUserSyncService = projectUserSyncService;
        this.projectEnvironmentSyncService = projectEnvironmentSyncService;
        this.bulkManagementService = bulkManagementService;
        this.termComparisonService = termComparisonService;
        this.pluginThemeComparisonService = pluginThemeComparisonService;
        this.postComparisonService = postComparisonService;
        this.adminAuthorizationService = adminAuthorizationService;
        this.currentActorService = currentActorService;
        this.generatedImageRepository = generatedImageRepository;
        this.generatedImageStorageService = generatedImageStorageService;
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> create(@Valid @RequestBody ProjectCreateRequest request) {
        adminAuthorizationService.requireAdmin();
        ProjectResponse response = projectService.createProject(request.name(), request.slug());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public List<ProjectResponse> list(
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder) {
        return projectService.listProjects(sortBy, sortOrder);
    }

    @GetMapping("/{id}")
    public ProjectResponse get(@PathVariable Long id) {
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

    @PutMapping("/{id}/css-selector-prefix")
    public ProjectResponse updateCssSelectorPrefix(
            @PathVariable Long id, @Valid @RequestBody UpdateProjectCssSelectorPrefixRequest request) {
        adminAuthorizationService.requireAdmin();
        return projectService.updateCssSelectorPrefix(id, request);
    }

    @PostMapping("/{id}/environments/sync")
    public ResponseEntity<Void> syncEnvironment(
            @PathVariable Long id, @Valid @RequestBody SyncEnvironmentRequest request) {
        adminAuthorizationService.requireAdmin();
        projectEnvironmentSyncService.sync(id, request.from(), request.to(), request.targets());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/bulk-management/apply")
    public BulkOperationLogResponse applyBulkOperation(
            @PathVariable Long id, @Valid @RequestBody ApplyToEnvironmentRequest request) {
        adminAuthorizationService.requireAdmin();
        if (request.operationType().requiresMasterEnvironment()) {
            String masterEnvironment = projectService.getProject(id).masterEnvironment();
            if (!masterEnvironment.equals(request.environment())) {
                throw new IllegalArgumentException(
                        "マスター環境(" + masterEnvironment + ")以外への作成・編集はできません");
            }
        }
        Long actorId = currentActorService.getCurrentActorId();
        BulkOperationLog log = bulkManagementService.applyToEnvironment(
                id, request.environment(), request.operationType(), request.value(),
                request.categorySlug(), request.categoryParentSlug(), request.categoryDescription(),
                request.categoryTargetSlug(), actorId);
        return BulkOperationLogResponse.from(log);
    }

    @PostMapping(value = "/{id}/bulk-management/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<BulkOperationLogResponse> runBulkOperationUpload(
            @PathVariable Long id,
            @RequestParam BulkOperationType operationType,
            @RequestPart("file") MultipartFile file) throws IOException {
        adminAuthorizationService.requireAdmin();
        Long actorId = currentActorService.getCurrentActorId();
        List<BulkOperationLog> logs = bulkManagementService.executeFromUpload(id, operationType, file, actorId);
        return logs.stream().map(BulkOperationLogResponse::from).toList();
    }

    /**
     * ComfyUIで生成済みの画像(generated_images)を、プロジェクトのlocal/test/production
     * 全環境へアセットとしてアップロードする(プロジェクト管理画面の画像生成パネル用)。
     */
    @PostMapping("/{id}/asset-images/{generatedImageId}/upload")
    public List<BulkOperationLogResponse> uploadAssetImage(
            @PathVariable Long id, @PathVariable Long generatedImageId) {
        adminAuthorizationService.requireAdmin();
        GeneratedImage image = generatedImageRepository.findById(generatedImageId)
                .orElseThrow(() -> new GeneratedImageNotFoundException("id: " + generatedImageId));
        byte[] data = generatedImageStorageService.load(image.getFilePath());
        String filename = "comfyui-" + generatedImageId + ".png";
        Long actorId = currentActorService.getCurrentActorId();
        List<BulkOperationLog> logs = bulkManagementService.uploadImageToAllEnvironments(
                id, data, filename, image.getMimeType(), actorId);
        return logs.stream().map(BulkOperationLogResponse::from).toList();
    }

    @GetMapping("/{id}/bulk-management/categories/comparison")
    public TermComparisonPage categoryComparison(
            @PathVariable Long id, @RequestParam(defaultValue = "0") int page) {
        adminAuthorizationService.requireAdmin();
        return termComparisonService.listCategoryComparison(id, page, 20);
    }

    @GetMapping("/{id}/bulk-management/tags/comparison")
    public TermComparisonPage tagComparison(
            @PathVariable Long id, @RequestParam(defaultValue = "0") int page) {
        adminAuthorizationService.requireAdmin();
        return termComparisonService.listTagComparison(id, page, 20);
    }

    @PostMapping("/{id}/bulk-management/categories/sync")
    public List<BulkOperationLogResponse> syncCategory(
            @PathVariable Long id, @Valid @RequestBody TermNameRequest request) {
        adminAuthorizationService.requireAdmin();
        Long actorId = currentActorService.getCurrentActorId();
        return termComparisonService.syncCategory(id, request.slug(), actorId).stream()
                .map(BulkOperationLogResponse::from).toList();
    }

    @PostMapping("/{id}/bulk-management/categories/delete-all")
    public List<BulkOperationLogResponse> deleteCategoryEverywhere(
            @PathVariable Long id, @Valid @RequestBody TermNameRequest request) {
        adminAuthorizationService.requireAdmin();
        Long actorId = currentActorService.getCurrentActorId();
        return termComparisonService.deleteCategoryEverywhere(id, request.slug(), actorId).stream()
                .map(BulkOperationLogResponse::from).toList();
    }

    @PostMapping("/{id}/bulk-management/categories/edit-sync")
    public List<BulkOperationLogResponse> editCategoryAndSync(
            @PathVariable Long id, @Valid @RequestBody EditTermRequest request) {
        adminAuthorizationService.requireAdmin();
        Long actorId = currentActorService.getCurrentActorId();
        return termComparisonService.editCategoryAndSync(id, request.targetSlug(), request.value(), request.slug(),
                request.parentSlug(), request.description(), actorId).stream()
                .map(BulkOperationLogResponse::from).toList();
    }

    @PostMapping("/{id}/bulk-management/categories/sync-all")
    public List<BulkOperationLogResponse> syncAllCategoriesToMaster(@PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        Long actorId = currentActorService.getCurrentActorId();
        return termComparisonService.syncAllCategoriesToMaster(id, actorId).stream()
                .map(BulkOperationLogResponse::from).toList();
    }

    @PostMapping("/{id}/bulk-management/tags/sync")
    public List<BulkOperationLogResponse> syncTag(
            @PathVariable Long id, @Valid @RequestBody TermNameRequest request) {
        adminAuthorizationService.requireAdmin();
        Long actorId = currentActorService.getCurrentActorId();
        return termComparisonService.syncTag(id, request.slug(), actorId).stream()
                .map(BulkOperationLogResponse::from).toList();
    }

    @PostMapping("/{id}/bulk-management/tags/delete-all")
    public List<BulkOperationLogResponse> deleteTagEverywhere(
            @PathVariable Long id, @Valid @RequestBody TermNameRequest request) {
        adminAuthorizationService.requireAdmin();
        Long actorId = currentActorService.getCurrentActorId();
        return termComparisonService.deleteTagEverywhere(id, request.slug(), actorId).stream()
                .map(BulkOperationLogResponse::from).toList();
    }

    @PostMapping("/{id}/bulk-management/tags/edit-sync")
    public List<BulkOperationLogResponse> editTagAndSync(
            @PathVariable Long id, @Valid @RequestBody EditTermRequest request) {
        adminAuthorizationService.requireAdmin();
        Long actorId = currentActorService.getCurrentActorId();
        return termComparisonService.editTagAndSync(id, request.targetSlug(), request.value(), request.slug(),
                request.parentSlug(), request.description(), actorId).stream()
                .map(BulkOperationLogResponse::from).toList();
    }

    @PostMapping("/{id}/bulk-management/tags/sync-all")
    public List<BulkOperationLogResponse> syncAllTagsToMaster(@PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        Long actorId = currentActorService.getCurrentActorId();
        return termComparisonService.syncAllTagsToMaster(id, actorId).stream()
                .map(BulkOperationLogResponse::from).toList();
    }

    @GetMapping("/{id}/bulk-management/plugins/comparison")
    public StatusComparisonPage pluginComparison(
            @PathVariable Long id, @RequestParam(defaultValue = "0") int page) {
        adminAuthorizationService.requireAdmin();
        return pluginThemeComparisonService.listPluginComparison(id, page, 20);
    }

    @GetMapping("/{id}/bulk-management/themes/comparison")
    public StatusComparisonPage themeComparison(
            @PathVariable Long id, @RequestParam(defaultValue = "0") int page) {
        adminAuthorizationService.requireAdmin();
        return pluginThemeComparisonService.listThemeComparison(id, page, 20);
    }

    @PostMapping("/{id}/bulk-management/plugins/reconcile")
    public List<BulkOperationLogResponse> reconcilePlugin(
            @PathVariable Long id, @Valid @RequestBody ReconcileStateRequest request) {
        adminAuthorizationService.requireAdmin();
        Long actorId = currentActorService.getCurrentActorId();
        return pluginThemeComparisonService.reconcilePlugin(id, request.slug(), request.changes(), actorId).stream()
                .map(BulkOperationLogResponse::from).toList();
    }

    @PostMapping("/{id}/bulk-management/themes/reconcile")
    public List<BulkOperationLogResponse> reconcileTheme(
            @PathVariable Long id, @Valid @RequestBody ReconcileStateRequest request) {
        adminAuthorizationService.requireAdmin();
        Long actorId = currentActorService.getCurrentActorId();
        return pluginThemeComparisonService.reconcileTheme(id, request.slug(), request.changes(), actorId).stream()
                .map(BulkOperationLogResponse::from).toList();
    }

    @PostMapping("/{id}/bulk-management/plugins/delete-all")
    public List<BulkOperationLogResponse> deletePluginEverywhere(
            @PathVariable Long id, @Valid @RequestBody DeleteSlugRequest request) {
        adminAuthorizationService.requireAdmin();
        Long actorId = currentActorService.getCurrentActorId();
        return pluginThemeComparisonService.deletePluginEverywhere(id, request.slug(), actorId).stream()
                .map(BulkOperationLogResponse::from).toList();
    }

    @PostMapping("/{id}/bulk-management/themes/delete-all")
    public List<BulkOperationLogResponse> deleteThemeEverywhere(
            @PathVariable Long id, @Valid @RequestBody DeleteSlugRequest request) {
        adminAuthorizationService.requireAdmin();
        Long actorId = currentActorService.getCurrentActorId();
        return pluginThemeComparisonService.deleteThemeEverywhere(id, request.slug(), actorId).stream()
                .map(BulkOperationLogResponse::from).toList();
    }

    @GetMapping("/{id}/bulk-management/posts/comparison")
    public PostComparisonPage postComparison(
            @PathVariable Long id,
            @RequestParam(defaultValue = "post") String postType,
            @RequestParam(defaultValue = "0") int page) {
        adminAuthorizationService.requireAdmin();
        return postComparisonService.listComparison(id, postType, page, 20);
    }

    @PostMapping("/{id}/bulk-management/posts/delete-all")
    public List<BulkOperationLogResponse> deletePostEverywhere(
            @PathVariable Long id,
            @RequestParam(defaultValue = "post") String postType,
            @Valid @RequestBody DeleteSlugRequest request) {
        adminAuthorizationService.requireAdmin();
        Long actorId = currentActorService.getCurrentActorId();
        return postComparisonService.deleteEverywhere(id, postType, request.slug(), actorId).stream()
                .map(BulkOperationLogResponse::from).toList();
    }

    @PostMapping("/{id}/bulk-management/posts/status-update")
    public List<BulkOperationLogResponse> updatePostStatusEverywhere(
            @PathVariable Long id,
            @RequestParam(defaultValue = "post") String postType,
            @Valid @RequestBody UpdatePostStatusRequest request) {
        adminAuthorizationService.requireAdmin();
        Long actorId = currentActorService.getCurrentActorId();
        return postComparisonService.updateStatusEverywhere(id, postType, request.slug(), request.status(), actorId)
                .stream().map(BulkOperationLogResponse::from).toList();
    }

    @GetMapping("/{id}/users")
    public List<ProjectUserResponse> listUsers(@PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        return projectUserSyncService.getProjectUsers(id);
    }

    @PostMapping("/{id}/users")
    public ResponseEntity<Void> addUser(@PathVariable Long id, @Valid @RequestBody AddProjectUserRequest request) {
        adminAuthorizationService.requireAdmin();
        projectUserSyncService.addUserToProject(id, request.userId(), request.wpRole());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/users/{userId}")
    public ResponseEntity<Void> updateUserRole(
            @PathVariable Long id, @PathVariable Long userId, @Valid @RequestBody UpdateProjectUserRequest request) {
        adminAuthorizationService.requireAdmin();
        projectUserSyncService.updateUserProjectRole(id, userId, request.wpRole());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/users/{userId}")
    public ResponseEntity<Void> removeUser(@PathVariable Long id, @PathVariable Long userId) {
        adminAuthorizationService.requireAdmin();
        projectUserSyncService.removeUserFromProject(id, userId);
        return ResponseEntity.noContent().build();
    }
}
