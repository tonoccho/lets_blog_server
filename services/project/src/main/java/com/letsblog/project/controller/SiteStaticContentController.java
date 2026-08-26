package com.letsblog.project.controller;

import com.letsblog.project.dto.GenerateStaticContentRequest;
import com.letsblog.project.dto.StaticContentResponse;
import com.letsblog.project.service.AdminAuthorizationService;
import com.letsblog.project.service.StaticContentGenerationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    public SiteStaticContentController(
            StaticContentGenerationService staticContentGenerationService,
            AdminAuthorizationService adminAuthorizationService) {
        this.staticContentGenerationService = staticContentGenerationService;
        this.adminAuthorizationService = adminAuthorizationService;
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
}
