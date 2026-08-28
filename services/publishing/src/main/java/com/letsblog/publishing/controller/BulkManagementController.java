package com.letsblog.publishing.controller;

import com.letsblog.publishing.client.MediaGeneratedImageClient;
import com.letsblog.publishing.domain.BulkOperationLog;
import com.letsblog.publishing.domain.BulkOperationType;
import com.letsblog.publishing.domain.Project;
import com.letsblog.publishing.dto.ApplyToAllEnvironmentsRequest;
import com.letsblog.publishing.dto.ApplyToEnvironmentRequest;
import com.letsblog.publishing.dto.BulkOperationLogResponse;
import com.letsblog.publishing.dto.DeleteSlugRequest;
import com.letsblog.publishing.dto.EditTermRequest;
import com.letsblog.publishing.dto.PostComparisonPage;
import com.letsblog.publishing.dto.ReconcileStateRequest;
import com.letsblog.publishing.dto.StatusComparisonPage;
import com.letsblog.publishing.dto.TermComparisonPage;
import com.letsblog.publishing.dto.TermNameRequest;
import com.letsblog.publishing.dto.UpdatePostStatusRequest;
import com.letsblog.publishing.service.AdminAuthorizationService;
import com.letsblog.publishing.service.BulkManagementService;
import com.letsblog.publishing.service.CurrentActorService;
import com.letsblog.publishing.service.PluginThemeComparisonService;
import com.letsblog.publishing.service.PostComparisonService;
import com.letsblog.publishing.service.ProjectService;
import com.letsblog.publishing.service.TermComparisonService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * プロジェクトの一括管理(bulk-management: カテゴリ/タグ/プラグイン/テーマ/投稿の環境間比較・同期)API。
 * legacy-apiの{@code com.letsblog.api.controller.ProjectController}のうち一括管理サブリソース
 * ({@code /api/projects/{id}/bulk-management/**}・{@code /api/projects/{id}/asset-images/**})を
 * publishing-serviceへ移設したもの(issue #708、Epic #551 C6-2)。プロジェクトのCRUD・環境紐付け・
 * 環境同期({@code /api/projects}の基本操作)はproject-service、プロジェクトユーザー管理・
 * css-selector-prefix等の設定はlegacy-apiに残る(移設対象外)。
 *
 * <p>gatewayは{@code /api/projects/*&#47;bulk-management/**}・{@code /api/projects/*&#47;asset-images/**}を
 * 本サービスへルーティングする(services/gateway/src/main/resources/application.yml参照)。
 */
@RestController
@RequestMapping("/api/projects")
public class BulkManagementController {

    private final ProjectService projectService;
    private final BulkManagementService bulkManagementService;
    private final TermComparisonService termComparisonService;
    private final PluginThemeComparisonService pluginThemeComparisonService;
    private final PostComparisonService postComparisonService;
    private final AdminAuthorizationService adminAuthorizationService;
    private final CurrentActorService currentActorService;
    private final MediaGeneratedImageClient mediaGeneratedImageClient;

    public BulkManagementController(
            ProjectService projectService,
            BulkManagementService bulkManagementService,
            TermComparisonService termComparisonService,
            PluginThemeComparisonService pluginThemeComparisonService,
            PostComparisonService postComparisonService,
            AdminAuthorizationService adminAuthorizationService,
            CurrentActorService currentActorService,
            MediaGeneratedImageClient mediaGeneratedImageClient) {
        this.projectService = projectService;
        this.bulkManagementService = bulkManagementService;
        this.termComparisonService = termComparisonService;
        this.pluginThemeComparisonService = pluginThemeComparisonService;
        this.postComparisonService = postComparisonService;
        this.adminAuthorizationService = adminAuthorizationService;
        this.currentActorService = currentActorService;
        this.mediaGeneratedImageClient = mediaGeneratedImageClient;
    }

    @PostMapping("/{id}/bulk-management/apply")
    public BulkOperationLogResponse applyBulkOperation(
            @PathVariable Long id, @Valid @RequestBody ApplyToEnvironmentRequest request) {
        adminAuthorizationService.requireAdmin();
        if (request.operationType().requiresMasterEnvironment()) {
            Project project = projectService.getProjectEntity(id);
            if (!project.getMasterEnvironment().equals(request.environment())) {
                throw new IllegalArgumentException(
                        "マスター環境(" + project.getMasterEnvironment() + ")以外への作成・編集はできません");
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
}
