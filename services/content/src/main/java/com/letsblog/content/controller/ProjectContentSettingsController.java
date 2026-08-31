package com.letsblog.content.controller;

import com.letsblog.content.dto.ProjectContentSettingsResponse;
import com.letsblog.content.service.AdminAuthorizationService;
import com.letsblog.content.service.ProjectContentSettingsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * プロジェクト単位のコンテンツ設定(CSSセレクタ接頭辞)の参照・更新。
 *
 * <p>{@code project_content_settings} の所有権は #576 で content-service({@code lbs_content})へ
 * 移っていたが、Web 向けのエンドポイントは legacy-api の {@code ProjectController} に残り、
 * 内部ブリッジ({@link InternalProjectContentSettingsController})経由で中継されていた。
 * #583 で legacy-api を解体した際に gateway のルートはこちらへ向けたが、
 * <b>受け口となるこのコントローラを作り忘れており、{@code PUT} が404になっていた</b>(issue #913)。
 *
 * <p>参照用の {@code GET} は新設である。移設前はプロジェクト詳細({@code GET /api/projects/{id}})が
 * cssSelectorPrefix を含めて返していたが、project-service の {@code ProjectResponse} は
 * 他サービス所有の設定を含まないため、個別に取得する口が要る(#913)。
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
public class ProjectContentSettingsController {

    private final ProjectContentSettingsService projectContentSettingsService;
    private final AdminAuthorizationService adminAuthorizationService;

    public ProjectContentSettingsController(
            ProjectContentSettingsService projectContentSettingsService,
            AdminAuthorizationService adminAuthorizationService) {
        this.projectContentSettingsService = projectContentSettingsService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    /** 未設定のプロジェクトは {@code cssSelectorPrefix=null} を返す(404にはしない)。 */
    @GetMapping("/content-settings")
    public ProjectContentSettingsResponse get(@PathVariable Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return new ProjectContentSettingsResponse(projectContentSettingsService.getCssSelectorPrefix(projectId));
    }

    /** カスタムタグの統合CSSに付ける接頭辞。空文字は未設定として保存する。 */
    @PutMapping("/css-selector-prefix")
    public ProjectContentSettingsResponse updateCssSelectorPrefix(
            @PathVariable Long projectId, @Valid @RequestBody UpdateCssSelectorPrefixRequest request) {
        adminAuthorizationService.requireAdmin();
        var updated = projectContentSettingsService.updateCssSelectorPrefix(
                projectId, blankToNull(request.cssSelectorPrefix()));
        return new ProjectContentSettingsResponse(updated.getCssSelectorPrefix());
    }

    public record UpdateCssSelectorPrefixRequest(@Size(max = 100) String cssSelectorPrefix) {
    }

    /**
     * 空文字は null(未設定)として保存する。移設前の legacy-api
     * {@code ProjectService#updateCssSelectorPrefix} と同じ扱い。
     */
    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
