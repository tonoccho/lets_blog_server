package com.letsblog.api.controller;

import com.letsblog.api.dto.GenerateStaticContentRequest;
import com.letsblog.api.dto.StaticContentResponse;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.StaticContentGenerationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    @Operation(summary = "サイトの静的コンテンツ一覧を取得",
            description = "指定サイトで生成済みのプライバシーポリシー・運営者情報を取得します")
    @ApiResponse(responseCode = "200", description = "静的コンテンツ一覧を返す")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "403", description = "管理者権限がありません")
    @GetMapping
    public List<StaticContentResponse> list(@Parameter(description = "サイトID") @PathVariable Long siteId) {
        adminAuthorizationService.requireAdmin();
        return staticContentGenerationService.listBySite(siteId);
    }

    @Operation(summary = "静的コンテンツを生成", description = "サイトのプラグイン構成をもとにLLMで静的コンテンツを生成します")
    @ApiResponse(responseCode = "200", description = "生成された静的コンテンツを返す")
    @ApiResponse(responseCode = "400", description = "リクエストボディが不正")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "403", description = "管理者権限がありません")
    @ApiResponse(responseCode = "404", description = "サイトが見つかりません")
    @ApiResponse(responseCode = "502", description = "プラグイン情報の取得またはLLM呼び出しに失敗")
    @PostMapping("/generate")
    public StaticContentResponse generate(
            @Parameter(description = "サイトID") @PathVariable Long siteId,
            @Valid @RequestBody GenerateStaticContentRequest request) {
        adminAuthorizationService.requireAdmin();
        return staticContentGenerationService.generate(siteId, request.contentType());
    }
}
