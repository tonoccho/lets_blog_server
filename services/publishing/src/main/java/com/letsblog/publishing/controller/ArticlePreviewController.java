package com.letsblog.publishing.controller;

import com.letsblog.publishing.dto.SignedPreviewUrlRequest;
import com.letsblog.publishing.dto.SignedPreviewUrlResponse;
import com.letsblog.publishing.service.AdminAuthorizationService;
import com.letsblog.publishing.service.ArticlePreviewService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * VSCode拡張の記事プレビューのうち、実サイトで表示する署名付きプレビューURLの発行(/signed-url)を受け付ける
 * (issue #1561)。旧プレビュー経路(テーマCSS取得・骨格差し替え・プレビュー用投稿削除)は、
 * プラグイン必須化に伴い issue #1564 で削除した。
 *
 * <p>記事本文のレンダリング(/render)はCMSへの依存を持たないためcontent-serviceが提供する
 * (issue #576。gatewayの{@code project-preview-render}ルートが/renderのみをcontent-serviceへ振り分ける)。
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

    /**
     * 投稿を作らずに実テーマの単一記事テンプレートで表示する、期限付きの署名付きプレビューURLを発行する
     * (issue #1561)。内容はwp-cliでletsblogプラグインへ渡す。
     */
    @PostMapping("/signed-url")
    public SignedPreviewUrlResponse signedUrl(
            @PathVariable Long projectId, @Valid @RequestBody SignedPreviewUrlRequest request) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return articlePreviewService.createSignedPreviewUrl(projectId, request);
    }
}
