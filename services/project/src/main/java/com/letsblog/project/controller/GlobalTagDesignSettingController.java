package com.letsblog.project.controller;

import com.letsblog.project.domain.EmbedTagType;
import com.letsblog.project.dto.GenerateTagDesignRequest;
import com.letsblog.project.dto.GenerateTagDesignResponse;
import com.letsblog.project.dto.SaveTagDesignSettingRequest;
import com.letsblog.project.dto.TagDesignSettingResponse;
import com.letsblog.project.dto.TagDesignSettingsOverviewResponse;
import com.letsblog.project.service.AdminAuthorizationService;
import com.letsblog.project.service.LetsblogSyncService;
import com.letsblog.project.service.TagDesignGenerationService;
import com.letsblog.project.service.TagDesignSettingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * プロジェクトに紐付いていないサイト向けの、グローバル既定タグデザインのAPI(issue #763)。
 *
 * <p>{@link TagDesignSettingController}がプロジェクト単位({@code project_id = ?})を扱うのに対し、
 * こちらは{@code project_id IS NULL}の1組を扱う。実体は同じ{@code tag_design_settings}テーブルで、
 * {@link TagDesignSettingService}にprojectId=nullを渡すだけ。
 *
 * <p><b>認可はadmin限定にする。</b>プロジェクト単位の設定は
 * {@code requireProjectMemberOrAdmin(projectId)}でメンバーにも許しているが、
 * グローバル既定は「どのプロジェクトにも属さない」設定であり、判定に使えるメンバーシップが無い。
 * 影響範囲も未紐付けサイト全体に及ぶため、admin限定が妥当な既定である。
 *
 * <p>パスを{@code /api/projects/global/...}のようにプロジェクト配下へ寄せなかったのは、
 * {@code {projectId}}が{@code Long}で束縛されており、"global"のような非数値を混ぜると
 * 型変換エラーとの区別がつかなくなるため。独立したパスにして曖昧さを無くしている。
 */
@RestController
@RequestMapping("/api/tag-design-settings")
public class GlobalTagDesignSettingController {

    private final TagDesignSettingService tagDesignSettingService;
    private final TagDesignGenerationService tagDesignGenerationService;
    private final AdminAuthorizationService adminAuthorizationService;
    private final LetsblogSyncService letsblogSyncService;

    public GlobalTagDesignSettingController(
            TagDesignSettingService tagDesignSettingService,
            TagDesignGenerationService tagDesignGenerationService,
            AdminAuthorizationService adminAuthorizationService,
            LetsblogSyncService letsblogSyncService) {
        this.letsblogSyncService = letsblogSyncService;
        this.tagDesignSettingService = tagDesignSettingService;
        this.tagDesignGenerationService = tagDesignGenerationService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @GetMapping
    public TagDesignSettingsOverviewResponse getOverview() {
        adminAuthorizationService.requireAdmin();
        return tagDesignSettingService.getOverview(null);
    }

    @PutMapping("/{tagType}")
    public TagDesignSettingResponse save(
            @PathVariable EmbedTagType tagType,
            @Valid @RequestBody SaveTagDesignSettingRequest request) {
        adminAuthorizationService.requireAdmin();
        TagDesignSettingResponse saved = tagDesignSettingService.save(null, tagType, request);
        // グローバル既定のデザインの変更は、すべてのプロジェクトのサイトへ同期し直す(issue #1558)。
        letsblogSyncService.requestAllSync();
        return saved;
    }

    @PostMapping("/{tagType}/generate")
    public GenerateTagDesignResponse generate(
            @PathVariable EmbedTagType tagType,
            @Valid @RequestBody GenerateTagDesignRequest request) {
        adminAuthorizationService.requireAdmin();
        String currentHtmlTemplate = tagDesignSettingService.resolveHtmlTemplate(null, tagType);
        return tagDesignGenerationService.generate(null, tagType, request.prompt(), currentHtmlTemplate);
    }
}
