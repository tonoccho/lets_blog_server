package com.letsblog.publishing.controller;

import com.letsblog.publishing.dto.RenderSkeletonRequest;
import com.letsblog.publishing.dto.SignedPreviewUrlRequest;
import com.letsblog.publishing.dto.SignedPreviewUrlResponse;
import com.letsblog.publishing.dto.ThemeCssResponse;
import com.letsblog.publishing.dto.ThemeSkeletonResponse;
import com.letsblog.publishing.service.AdminAuthorizationService;
import com.letsblog.publishing.service.ArticlePreviewService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * VSCode拡張の記事プレビューのうち、CMSへの深い依存を持つテーマCSS取得(/theme-css)・
 * 骨格差し替え(/skeleton)・プレビュー用投稿削除(/preview-post)を受け付ける。legacy-apiの
 * {@code ArticlePreviewController}をpublishing-serviceへ移設したもの(issue #712、Epic #551 C6-6)。
 *
 * <p>記事本文のレンダリング(/render)はCMSへの依存を持たないため先にcontent-serviceへ移設済みで
 * (issue #576)、同じ{@code /api/projects/{projectId}/preview}配下のパスをcontent-service側の
 * ArticlePreviewControllerが引き続き提供する(gatewayの{@code project-preview-render}ルートが
 * /renderのみをcontent-serviceへ、残りを本サービスへ振り分ける)。
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
     * プレビューに適用するテーマCSSを返す。siteId未指定時はマスター環境のサイトを対象とする
     * (VSCode拡張のプレビューで、ローカル/テスト/本番のどのサイトの見た目で確認するかを選べるようにする)。
     */
    @GetMapping("/theme-css")
    public ThemeCssResponse themeCss(
            @PathVariable Long projectId, @RequestParam(required = false) Long siteId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return articlePreviewService.fetchThemeCss(projectId, siteId);
    }

    /**
     * サイト内の既存記事ページを骨格として流用し、実テーマのDOM構造を保ったまま
     * タイトル/本文/アイキャッチをプレビュー対象記事の内容へ差し替えたHTML断片を返す。
     */
    @PostMapping("/skeleton")
    public ThemeSkeletonResponse skeleton(
            @PathVariable Long projectId, @Valid @RequestBody RenderSkeletonRequest request) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return articlePreviewService.renderSkeleton(
                projectId, request.siteId(), request.title(), request.contentHtml(), request.featuredImageDataUri(),
                request.existingPreviewPostId(), request.slug(), request.categories(), request.tags());
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

    /**
     * {@link #skeleton}がローカル/テスト環境向けに作成した非公開プレビュー投稿を削除する
     * (VSCode拡張側でプレビューパネルを閉じた際に呼ばれる)。
     */
    @DeleteMapping("/preview-post")
    public void deletePreviewPost(
            @PathVariable Long projectId, @RequestParam Long siteId, @RequestParam String postId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        articlePreviewService.deletePreviewPost(projectId, siteId, postId);
    }
}
