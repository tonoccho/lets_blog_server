package com.letsblog.content.controller;

import com.letsblog.content.dto.ProjectContentSettingsBridgeResponse;
import com.letsblog.content.service.ProjectContentSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * legacy-api側のProjectService(プロジェクト詳細画面のcssSelectorPrefix表示・更新、issue #571)向けの
 * 内部ブリッジ(issue #576)。project_content_settingsの所有権がcontent-serviceへ移った(ADR-0004、
 * lbs_contentスキーマ)ため、legacy-api側はJPAでの直接アクセスができなくなり、この内部ブリッジ経由で
 * 読み書きする。
 */
@RestController
public class InternalProjectContentSettingsController {

    private final ProjectContentSettingsService projectContentSettingsService;

    public InternalProjectContentSettingsController(ProjectContentSettingsService projectContentSettingsService) {
        this.projectContentSettingsService = projectContentSettingsService;
    }

    /** ProjectService#toResponseが使う。未設定のプロジェクトはcssSelectorPrefix=nullを返す(404にはしない)。 */
    @GetMapping("/api/internal/content/projects/{projectId}/content-settings")
    public ProjectContentSettingsBridgeResponse get(@PathVariable Long projectId) {
        return new ProjectContentSettingsBridgeResponse(
                projectContentSettingsService.getCssSelectorPrefix(projectId));
    }

    /** ProjectService#updateCssSelectorPrefixが使う。 */
    @PutMapping("/api/internal/content/projects/{projectId}/content-settings")
    public ProjectContentSettingsBridgeResponse update(
            @PathVariable Long projectId, @RequestBody ProjectContentSettingsBridgeResponse request) {
        var updated = projectContentSettingsService.updateCssSelectorPrefix(projectId, request.cssSelectorPrefix());
        return new ProjectContentSettingsBridgeResponse(updated.getCssSelectorPrefix());
    }
}
