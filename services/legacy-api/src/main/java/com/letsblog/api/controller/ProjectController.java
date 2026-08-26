package com.letsblog.api.controller;

import com.letsblog.api.dto.AddProjectUserRequest;
import com.letsblog.api.dto.ApplyToAllEnvironmentsRequest;
import com.letsblog.api.dto.ApplyToEnvironmentRequest;
import com.letsblog.api.dto.BulkOperationLogResponse;
import com.letsblog.api.dto.DeleteSlugRequest;
import com.letsblog.api.dto.EditTermRequest;
import com.letsblog.api.dto.PostComparisonPage;
import com.letsblog.api.dto.ProjectResponse;
import com.letsblog.api.dto.ProjectUserResponse;
import com.letsblog.api.dto.ReconcileStateRequest;
import com.letsblog.api.dto.StatusComparisonPage;
import com.letsblog.api.dto.TermComparisonPage;
import com.letsblog.api.dto.TermNameRequest;
import com.letsblog.api.dto.UpdateArticleImageResizeDefaultRequest;
import com.letsblog.api.dto.UpdateImageContentFilterSettingsRequest;
import com.letsblog.api.dto.UpdateImageGenerationPromptDefaultsRequest;
import com.letsblog.api.dto.UpdateImageGenerationSizeDefaultsRequest;
import com.letsblog.api.dto.UpdatePostStatusRequest;
import com.letsblog.api.dto.UpdateProjectCssSelectorPrefixRequest;
import com.letsblog.api.dto.UpdateProjectUserRequest;
import com.letsblog.api.ai.MediaGeneratedImageClient;
import com.letsblog.api.domain.BulkOperationLog;
import com.letsblog.api.domain.BulkOperationType;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.BulkManagementService;
import com.letsblog.api.service.CurrentActorService;
import com.letsblog.api.service.PluginThemeComparisonService;
import com.letsblog.api.service.PostComparisonService;
import com.letsblog.api.service.ProjectService;
import com.letsblog.api.service.ProjectUserSyncService;
import com.letsblog.api.service.TermComparisonService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * プロジェクトの一括管理(bulk-management: カテゴリ/タグ/プラグイン/テーマ/投稿の環境間比較・同期)・
 * プロジェクトユーザー管理API。
 *
 * <p>プロジェクトのCRUD・環境紐付け・環境同期(/api/projects の基本操作)はproject-serviceへ移設した
 * (issue #577 stage2)。本コントローラは、project-serviceへ移設していないドメイン
 * (BulkManagementService/TermComparisonService/PluginThemeComparisonService/PostComparisonService/
 * ProjectUserSyncServiceは、いずれも本stageのスコープ外)向けのサブリソースのみを引き続き提供する。
 * gateway側は、これらのサブパス({@code /bulk-management/**}・{@code /users/**}・
 * {@code /asset-images/**})のみlegacy-apiへ、それ以外の{@code /api/projects/**}はproject-serviceへ
 * ルーティングする(PR説明を参照)。
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectUserSyncService projectUserSyncService;
    private final BulkManagementService bulkManagementService;
    private final TermComparisonService termComparisonService;
    private final PluginThemeComparisonService pluginThemeComparisonService;
    private final PostComparisonService postComparisonService;
    private final AdminAuthorizationService adminAuthorizationService;
    private final CurrentActorService currentActorService;
    private final MediaGeneratedImageClient mediaGeneratedImageClient;

    public ProjectController(
            ProjectService projectService,
            ProjectUserSyncService projectUserSyncService,
            BulkManagementService bulkManagementService,
            TermComparisonService termComparisonService,
            PluginThemeComparisonService pluginThemeComparisonService,
            PostComparisonService postComparisonService,
            AdminAuthorizationService adminAuthorizationService,
            CurrentActorService currentActorService,
            MediaGeneratedImageClient mediaGeneratedImageClient) {
        this.projectService = projectService;
        this.projectUserSyncService = projectUserSyncService;
        this.bulkManagementService = bulkManagementService;
        this.termComparisonService = termComparisonService;
        this.pluginThemeComparisonService = pluginThemeComparisonService;
        this.postComparisonService = postComparisonService;
        this.adminAuthorizationService = adminAuthorizationService;
        this.currentActorService = currentActorService;
        this.mediaGeneratedImageClient = mediaGeneratedImageClient;
    }

    /**
     * css-selector-prefix・画像生成デフォルト設定は、project_content_settings(content-service)・
     * project_image_settings(概念上media-service所有だがlegacy-apiのローカルテーブルのまま、issue #571)
     * への内部ブリッジ/委譲を伴い、project-serviceへ移設したProjectService(#577 stage2)には無い
     * 依存(ContentServiceClient/ProjectImageSettingsService)のため、legacy-api側のProjectServiceに
     * 残したこれらのメソッドをそのまま呼び出す(#577の既知の制限。PR説明を参照)。
     */
    @PutMapping("/{id}/css-selector-prefix")
    public ProjectResponse updateCssSelectorPrefix(
            @PathVariable Long id, @Valid @RequestBody UpdateProjectCssSelectorPrefixRequest request) {
        adminAuthorizationService.requireAdmin();
        return projectService.updateCssSelectorPrefix(id, request);
    }

    /** issue #293: 画像生成時のnegative prompt/画質プロンプトのデフォルト値。 */
    @PutMapping("/{id}/image-generation-prompt-defaults")
    public ProjectResponse updateImageGenerationPromptDefaults(
            @PathVariable Long id,
            @Valid @RequestBody UpdateImageGenerationPromptDefaultsRequest request) {
        adminAuthorizationService.requireAdmin();
        return projectService.updateImageGenerationPromptDefaults(id, request);
    }

    /** issue #292: 画像生成時のデフォルトサイズ。 */
    @PutMapping("/{id}/image-generation-size-defaults")
    public ProjectResponse updateImageGenerationSizeDefaults(
            @PathVariable Long id,
            @Valid @RequestBody UpdateImageGenerationSizeDefaultsRequest request) {
        adminAuthorizationService.requireAdmin();
        return projectService.updateImageGenerationSizeDefaults(id, request);
    }

    /** issue #291: 記事投稿時に画像をリサイズする長編の目標px。 */
    @PutMapping("/{id}/article-image-resize-default")
    public ProjectResponse updateArticleImageResizeDefault(
            @PathVariable Long id,
            @Valid @RequestBody UpdateArticleImageResizeDefaultRequest request) {
        adminAuthorizationService.requireAdmin();
        return projectService.updateArticleImageResizeDefault(id, request);
    }

    /** issue #532: 画像生成時の不適切コンテンツ(性的/暴力的/差別的表現)のカテゴリ別禁止設定。 */
    @PutMapping("/{id}/image-content-filter-settings")
    public ProjectResponse updateImageContentFilterSettings(
            @PathVariable Long id,
            @Valid @RequestBody UpdateImageContentFilterSettingsRequest request) {
        adminAuthorizationService.requireAdmin();
        return projectService.updateImageContentFilterSettings(id, request);
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

    /**
     * slugベースのプラグイン/テーマインストールを、紐付いている全環境へ一括実行する(issue #393)。
     */
    @PostMapping("/{id}/bulk-management/apply-all")
    public List<BulkOperationLogResponse> applyBulkOperationToAllEnvironments(
            @PathVariable Long id, @Valid @RequestBody ApplyToAllEnvironmentsRequest request) {
        adminAuthorizationService.requireAdmin();
        Long actorId = currentActorService.getCurrentActorId();
        List<BulkOperationLog> logs = bulkManagementService.applyToAllEnvironments(
                id, request.operationType(), request.value(), actorId);
        return logs.stream().map(BulkOperationLogResponse::from).toList();
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
     * ComfyUIで生成済みの画像(generated_images、media-serviceが所有。issue #573)を、
     * プロジェクトのlocal/test/production全環境へアセットとしてアップロードする
     * (プロジェクト管理画面の画像生成パネル用)。画像バイト列はmedia-service経由で取得する
     * (常にimage/pngとして保存されている前提、既存の挙動を踏襲)。
     */
    @PostMapping("/{id}/asset-images/{generatedImageId}/upload")
    public List<BulkOperationLogResponse> uploadAssetImage(
            @PathVariable Long id, @PathVariable Long generatedImageId) {
        adminAuthorizationService.requireAdmin();
        byte[] data = mediaGeneratedImageClient.fetchImageFile(generatedImageId);
        String filename = "comfyui-" + generatedImageId + ".png";
        Long actorId = currentActorService.getCurrentActorId();
        List<BulkOperationLog> logs = bulkManagementService.uploadImageToAllEnvironments(
                id, data, filename, "image/png", actorId);
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
