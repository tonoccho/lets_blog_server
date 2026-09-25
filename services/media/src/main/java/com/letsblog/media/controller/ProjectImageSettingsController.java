package com.letsblog.media.controller;

import com.letsblog.media.dto.ProjectImageSettingsResponse;
import com.letsblog.media.dto.UpdateArticleImageResizeDefaultRequest;
import com.letsblog.media.dto.UpdateImageContentFilterSettingsRequest;
import com.letsblog.media.dto.UpdateImageGenerationPromptDefaultsRequest;
import com.letsblog.media.dto.UpdateImageGenerationSizeDefaultsRequest;
import com.letsblog.media.client.ProjectServiceClient;
import com.letsblog.media.service.AdminAuthorizationService;
import com.letsblog.media.service.ProjectImageSettingsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * プロジェクト単位の画像生成設定({@code project_image_settings})の参照・更新。
 * issue #583でlegacy-apiの{@code ProjectController}から移設した。
 *
 * <p>移設前は更新後に{@code ProjectResponse}(プロジェクト全体)を返していたが、
 * プロジェクト本体の所有権はproject-service(#577 stage2)にあり組み立てられないため、
 * 更新した設定そのもの({@link ProjectImageSettingsResponse})を返す。
 * 呼び出し側(Web)は戻り値を使っていないため影響しない。
 *
 * <p>参照用の{@code GET}を新設した。移設前はプロジェクト詳細({@code GET /api/projects/{id}})が
 * これらを含めて返していたが、project-serviceの{@code ProjectResponse}は含めないため、
 * 個別に取得する口が要る。Web側の追随は #913 で行う。
 */
@RestController
@RequestMapping("/api/projects/{id}")
public class ProjectImageSettingsController {

    private final ProjectImageSettingsService projectImageSettingsService;
    private final ProjectServiceClient projectServiceClient;
    private final AdminAuthorizationService adminAuthorizationService;

    public ProjectImageSettingsController(
            ProjectImageSettingsService projectImageSettingsService,
            ProjectServiceClient projectServiceClient,
            AdminAuthorizationService adminAuthorizationService) {
        this.projectImageSettingsService = projectImageSettingsService;
        this.projectServiceClient = projectServiceClient;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    /** 未設定(1行も無い)のプロジェクトは、全フィールドが{@code null}の応答を返す。 */
    @GetMapping("/image-settings")
    public ProjectImageSettingsResponse get(@PathVariable Long id) {
        adminAuthorizationService.requireProjectMemberOrAdmin(id);
        projectServiceClient.requireProjectExists(id);
        return projectImageSettingsService.findByProjectId(id)
                .map(ProjectImageSettingsResponse::from)
                .orElseGet(() -> new ProjectImageSettingsResponse(
                        id, null, null, null, null, null, null, null, null, null, null));
    }

    /** issue #293: 画像生成時のnegative prompt/画質プロンプトのデフォルト値。 */
    @PutMapping("/image-generation-prompt-defaults")
    public ProjectImageSettingsResponse updateImageGenerationPromptDefaults(
            @PathVariable Long id, @Valid @RequestBody UpdateImageGenerationPromptDefaultsRequest request) {
        adminAuthorizationService.requireAdmin();
        projectServiceClient.requireProjectExists(id);
        return ProjectImageSettingsResponse.from(projectImageSettingsService.updateImageGenerationPromptDefaults(
                id, blankToNull(request.defaultNegativePrompt()), blankToNull(request.defaultQualityPrompt())));
    }

    /** issue #292: 画像生成時のデフォルトサイズ。 */
    @PutMapping("/image-generation-size-defaults")
    public ProjectImageSettingsResponse updateImageGenerationSizeDefaults(
            @PathVariable Long id, @Valid @RequestBody UpdateImageGenerationSizeDefaultsRequest request) {
        adminAuthorizationService.requireAdmin();
        projectServiceClient.requireProjectExists(id);
        return ProjectImageSettingsResponse.from(projectImageSettingsService.updateImageGenerationSizeDefaults(
                id, request.defaultGeneratedImageWidth(), request.defaultGeneratedImageHeight()));
    }

    /** issue #291: 記事投稿時に画像をリサイズする長編の目標px。 */
    @PutMapping("/article-image-resize-default")
    public ProjectImageSettingsResponse updateArticleImageResizeDefault(
            @PathVariable Long id, @Valid @RequestBody UpdateArticleImageResizeDefaultRequest request) {
        adminAuthorizationService.requireAdmin();
        projectServiceClient.requireProjectExists(id);
        return ProjectImageSettingsResponse.from(projectImageSettingsService.updateArticleImageResizeDefault(
                id, request.defaultArticleImageLongEdgePx()));
    }

    /** issue #532: 画像生成時の不適切コンテンツ(性的/暴力的/差別的表現)のカテゴリ別禁止設定。 */
    @PutMapping("/image-content-filter-settings")
    public ProjectImageSettingsResponse updateImageContentFilterSettings(
            @PathVariable Long id, @Valid @RequestBody UpdateImageContentFilterSettingsRequest request) {
        adminAuthorizationService.requireAdmin();
        projectServiceClient.requireProjectExists(id);
        return ProjectImageSettingsResponse.from(projectImageSettingsService.updateImageContentFilterSettings(
                id, request.blockSexualContent(), request.blockViolentContent(),
                request.blockDiscriminatoryContent()));
    }

    /**
     * 空文字はnull(=プロジェクト単位の上書きなし)として保存する。移設前の
     * legacy-api {@code ProjectService#updateImageGenerationPromptDefaults}と同じ扱い。
     */
    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
