package com.letsblog.project.controller;

import com.letsblog.project.domain.EmbedTagType;
import com.letsblog.project.dto.GenerateTagDesignRequest;
import com.letsblog.project.dto.GenerateTagDesignResponse;
import com.letsblog.project.dto.GenerationJobResponse;
import com.letsblog.project.dto.SaveTagDesignSettingRequest;
import com.letsblog.project.dto.TagDesignSettingResponse;
import com.letsblog.project.dto.TagDesignSettingsOverviewResponse;
import com.letsblog.project.service.AdminAuthorizationService;
import com.letsblog.project.service.LetsblogSyncService;
import com.letsblog.project.service.TagDesignGenerationService;
import com.letsblog.project.service.TagDesignSettingService;
import com.letsblog.project.service.TextGenerationJobStarter;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * [toc]/[blogcard]/[amazon] 組み込みタグのデザインカスタマイズ画面(プロジェクト単位)向けAPI。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/tag-design-settings")
public class TagDesignSettingController {

    private final TagDesignSettingService tagDesignSettingService;
    private final TagDesignGenerationService tagDesignGenerationService;
    private final AdminAuthorizationService adminAuthorizationService;
    private final LetsblogSyncService letsblogSyncService;
    private final TextGenerationJobStarter textGenerationJobStarter;

    public TagDesignSettingController(
            TagDesignSettingService tagDesignSettingService,
            TagDesignGenerationService tagDesignGenerationService,
            AdminAuthorizationService adminAuthorizationService,
            LetsblogSyncService letsblogSyncService,
            TextGenerationJobStarter textGenerationJobStarter) {
        this.letsblogSyncService = letsblogSyncService;
        this.textGenerationJobStarter = textGenerationJobStarter;
        this.tagDesignSettingService = tagDesignSettingService;
        this.tagDesignGenerationService = tagDesignGenerationService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @GetMapping
    public TagDesignSettingsOverviewResponse getOverview(@PathVariable Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return tagDesignSettingService.getOverview(projectId);
    }

    @PutMapping("/{tagType}")
    public TagDesignSettingResponse save(
            @PathVariable Long projectId,
            @PathVariable EmbedTagType tagType,
            @Valid @RequestBody SaveTagDesignSettingRequest request) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        TagDesignSettingResponse saved = tagDesignSettingService.save(projectId, tagType, request);
        // 組み込みタグのデザインは統合CSSに含まれるため、そのプロジェクトのサイトへ同期し直す(issue #1558)。
        letsblogSyncService.requestProjectSync(projectId);
        return saved;
    }

    @PostMapping("/{tagType}/generate")
    public GenerateTagDesignResponse generate(
            @PathVariable Long projectId,
            @PathVariable EmbedTagType tagType,
            @Valid @RequestBody GenerateTagDesignRequest request) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        String currentHtmlTemplate = tagDesignSettingService.resolveHtmlTemplate(projectId, tagType);
        return tagDesignGenerationService.generate(projectId, tagType, request.prompt(), currentHtmlTemplate);
    }

    /**
     * タグデザインのAI生成を非同期ジョブとして受理する(issue #1409)。生成の完了を待たずにジョブIDを返し、
     * 状態と結果(CSS/HTML)は{@code GET /api/generation-jobs/{id}}で引く。生成結果は設定へ書かれず、
     * 利用者が確認して{@code PUT /{tagType}}で「保存」する。同期の{@link #generate}は変えない。認可は同じ。
     */
    @PostMapping("/{tagType}/generate/jobs")
    public ResponseEntity<GenerationJobResponse> generateJob(
            @PathVariable Long projectId,
            @PathVariable EmbedTagType tagType,
            @Valid @RequestBody GenerateTagDesignRequest request) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return ResponseEntity.accepted()
                .body(GenerationJobResponse.from(textGenerationJobStarter.startTagDesign(projectId, tagType, request.prompt())));
    }
}
