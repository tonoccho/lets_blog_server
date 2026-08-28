package com.letsblog.api.controller;

import com.letsblog.api.dto.AddProjectUserRequest;
import com.letsblog.api.dto.ProjectResponse;
import com.letsblog.api.dto.ProjectUserResponse;
import com.letsblog.api.dto.UpdateArticleImageResizeDefaultRequest;
import com.letsblog.api.dto.UpdateImageContentFilterSettingsRequest;
import com.letsblog.api.dto.UpdateImageGenerationPromptDefaultsRequest;
import com.letsblog.api.dto.UpdateImageGenerationSizeDefaultsRequest;
import com.letsblog.api.dto.UpdateProjectCssSelectorPrefixRequest;
import com.letsblog.api.dto.UpdateProjectUserRequest;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.ProjectService;
import com.letsblog.api.service.ProjectUserSyncService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * プロジェクトユーザー管理・css-selector-prefix/画像生成デフォルト設定API。
 *
 * <p>プロジェクトのCRUD・環境紐付け・環境同期(/api/projects の基本操作)はproject-serviceへ移設した
 * (issue #577 stage2)。一括管理(bulk-management: カテゴリ/タグ/プラグイン/テーマ/投稿の環境間比較・
 * 同期・アセット画像アップロード)は、CmsAdapter・SSH実行への深い依存のためBulkManagementService等
 * 一式と共にpublishing-serviceへ移設した({@link com.letsblog.publishing.controller.BulkManagementController}
 * 参照、issue #708、Epic #551 C6-2)。本コントローラは、project-serviceへ移設していないドメイン
 * (css-selector-prefix・画像生成デフォルト設定・ProjectUserSyncService)向けのサブリソースのみを
 * 引き続き提供する。gateway側は、これらのサブパス({@code /users/**})のみlegacy-apiへ、
 * {@code /bulk-management/**}・{@code /asset-images/**}はpublishing-serviceへ、それ以外の
 * {@code /api/projects/**}はproject-serviceへルーティングする
 * (services/gateway/src/main/resources/application.yml参照)。
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectUserSyncService projectUserSyncService;
    private final AdminAuthorizationService adminAuthorizationService;

    public ProjectController(
            ProjectService projectService,
            ProjectUserSyncService projectUserSyncService,
            AdminAuthorizationService adminAuthorizationService) {
        this.projectService = projectService;
        this.projectUserSyncService = projectUserSyncService;
        this.adminAuthorizationService = adminAuthorizationService;
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
