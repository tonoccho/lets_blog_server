package com.letsblog.content.controller;

import com.letsblog.content.dto.RenderPreviewRequest;
import com.letsblog.content.dto.RenderPreviewResponse;
import com.letsblog.content.service.AdminAuthorizationService;
import com.letsblog.content.service.ArticlePreviewService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * legacy-apiのArticlePreviewControllerのうち、記事本文のレンダリングパイプライン(/render、
 * CMSへの依存を持たない)のみを移設する(issue #576)。テーマCSS取得(/theme-css)・骨格差し替え
 * (/skeleton)・プレビュー用投稿の削除(/preview-post)は、Site/CMSアダプタへの深い依存があり
 * legacy-apiに残した(同じ{@code /api/projects/{projectId}/preview}配下のパスをlegacy-api側の
 * ArticlePreviewControllerが引き続き提供する)。
 */
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
}
