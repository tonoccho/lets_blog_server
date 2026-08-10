package com.letsblog.api.controller;

import com.letsblog.api.dto.RenderPreviewRequest;
import com.letsblog.api.dto.RenderPreviewResponse;
import com.letsblog.api.dto.ThemeCssResponse;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.ArticlePreviewService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/preview")
public class ArticlePreviewController {

    private final ArticlePreviewService articlePreviewService;
    private final AdminAuthorizationService adminAuthorizationService;

    public ArticlePreviewController(
            ArticlePreviewService articlePreviewService, AdminAuthorizationService adminAuthorizationService) {
        this.articlePreviewService = articlePreviewService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @PostMapping("/render")
    public RenderPreviewResponse render(
            @PathVariable Long projectId, @Valid @RequestBody RenderPreviewRequest request) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return new RenderPreviewResponse(articlePreviewService.renderHtml(projectId, request.markdown()));
    }

    /**
     * プレビューに適用するテーマCSSを返す。siteId未指定時はマスター環境のサイトを対象とする
     * (VSCode拡張のプレビューで、ローカル/テスト/本番のどのサイトの見た目で確認するかを選べるようにする)。
     */
    @GetMapping("/theme-css")
    public ThemeCssResponse themeCss(
            @PathVariable Long projectId, @RequestParam(required = false) Long siteId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return articlePreviewService.fetchThemeCss(projectId, siteId);
    }
}
