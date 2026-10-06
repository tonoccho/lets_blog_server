package com.letsblog.project.controller;

import com.letsblog.project.domain.StaticContentType;
import com.letsblog.project.dto.GenerateStaticContentRequest;
import com.letsblog.project.dto.GenerationJobResponse;
import com.letsblog.project.dto.SaveStaticContentRequest;
import com.letsblog.project.dto.StaticContentResponse;
import com.letsblog.project.service.AdminAuthorizationService;
import com.letsblog.project.service.StaticContentGenerationService;
import com.letsblog.project.service.TextGenerationJobStarter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** サイトの静的コンテンツ(プライバシーポリシー・運営者情報)API(issue #577 stage2、legacy-apiから移設)。 */
@Tag(name = "StaticContent", description = "サイトの静的コンテンツ(プライバシーポリシー・運営者情報)API")
@RestController
@RequestMapping("/api/sites/{siteId}/static-content")
public class SiteStaticContentController {

    private final StaticContentGenerationService staticContentGenerationService;
    private final AdminAuthorizationService adminAuthorizationService;
    private final TextGenerationJobStarter textGenerationJobStarter;

    public SiteStaticContentController(
            StaticContentGenerationService staticContentGenerationService,
            AdminAuthorizationService adminAuthorizationService,
            TextGenerationJobStarter textGenerationJobStarter) {
        this.staticContentGenerationService = staticContentGenerationService;
        this.adminAuthorizationService = adminAuthorizationService;
        this.textGenerationJobStarter = textGenerationJobStarter;
    }

    @Operation(summary = "サイトの静的コンテンツ一覧を取得")
    @GetMapping
    public List<StaticContentResponse> list(@Parameter(description = "サイトID") @PathVariable Long siteId) {
        adminAuthorizationService.requireAdmin();
        return staticContentGenerationService.listBySite(siteId);
    }

    @Operation(summary = "静的コンテンツを生成", description = "サイトのプラグイン構成をもとにLLMで静的コンテンツを生成します")
    @PostMapping("/generate")
    public StaticContentResponse generate(
            @Parameter(description = "サイトID") @PathVariable Long siteId,
            @Valid @RequestBody GenerateStaticContentRequest request) {
        adminAuthorizationService.requireAdmin();
        return staticContentGenerationService.generate(siteId, request.contentType());
    }

    /**
     * 静的コンテンツの生成を非同期ジョブとして受理する(issue #1409)。生成の完了を待たずにジョブIDを返し、
     * 状態と結果(生成した本文)は{@code GET /api/generation-jobs/{id}}で引く。生成結果は
     * {@code static_content}へ書かれず、利用者が確認して{@code PUT .../{contentType}}で「保存」する。
     * 同期の{@link #generate}(生成と同時に保存する)は変えない。認可は同じ(admin限定)。
     */
    @Operation(summary = "静的コンテンツの生成をジョブとして要求",
            description = "生成の完了を待たずにジョブIDを返します。状態と結果は GET /api/generation-jobs/{id} で取得します")
    @ApiResponse(responseCode = "202", description = "ジョブとして受理されました")
    @PostMapping("/generate/jobs")
    public ResponseEntity<GenerationJobResponse> generateJob(
            @Parameter(description = "サイトID") @PathVariable Long siteId,
            @Valid @RequestBody GenerateStaticContentRequest request) {
        adminAuthorizationService.requireAdmin();
        return ResponseEntity.accepted()
                .body(GenerationJobResponse.from(textGenerationJobStarter.startStaticContent(siteId, request.contentType())));
    }

    /**
     * 静的コンテンツの「保存」(issue #1409)。生成結果を確認した利用者が既存の{@code static_content}へ書く。
     * 生成以外の書き込み経路が無かったので追加した。下書きではなく通常の保存で、同じサイト・種別は上書きする。
     */
    @Operation(summary = "静的コンテンツを保存", description = "サイトの静的コンテンツを保存します(同じ種別があれば上書き)")
    @PutMapping("/{contentType}")
    public StaticContentResponse save(
            @Parameter(description = "サイトID") @PathVariable Long siteId,
            @Parameter(description = "静的コンテンツの種別") @PathVariable StaticContentType contentType,
            @Valid @RequestBody SaveStaticContentRequest request) {
        adminAuthorizationService.requireAdmin();
        return staticContentGenerationService.save(siteId, contentType, request.body());
    }
}
