package com.letsblog.content.controller;

import com.letsblog.content.dto.CustomTagPreviewRequest;
import com.letsblog.content.dto.CustomTagPreviewResponse;
import com.letsblog.content.dto.CustomTagResponse;
import com.letsblog.content.service.AdminAuthorizationService;
import com.letsblog.content.service.AmazonTagRenderService;
import com.letsblog.content.service.BlogCardTagRenderService;
import com.letsblog.content.service.CustomTagRenderService;
import com.letsblog.content.service.CustomTagService;
import com.letsblog.content.service.RenderedContentWrapperService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * プロジェクト詳細のカスタムタグ画面向けAPI。グローバルタグを含まず、プロジェクトのタグのみを返す(issue #157)。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/custom-tags")
public class ProjectCustomTagController {

    private final CustomTagService customTagService;
    private final CustomTagRenderService customTagRenderService;
    private final BlogCardTagRenderService blogCardTagRenderService;
    private final AmazonTagRenderService amazonTagRenderService;
    private final RenderedContentWrapperService renderedContentWrapperService;
    private final AdminAuthorizationService adminAuthorizationService;

    public ProjectCustomTagController(
            CustomTagService customTagService,
            CustomTagRenderService customTagRenderService,
            BlogCardTagRenderService blogCardTagRenderService,
            AmazonTagRenderService amazonTagRenderService,
            RenderedContentWrapperService renderedContentWrapperService,
            AdminAuthorizationService adminAuthorizationService) {
        this.customTagService = customTagService;
        this.customTagRenderService = customTagRenderService;
        this.blogCardTagRenderService = blogCardTagRenderService;
        this.amazonTagRenderService = amazonTagRenderService;
        this.renderedContentWrapperService = renderedContentWrapperService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @GetMapping
    public List<CustomTagResponse> list(@PathVariable Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return customTagService.listByProject(projectId);
    }

    @GetMapping("/css-bundle")
    public ResponseEntity<byte[]> cssBundle(@PathVariable Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        byte[] css = customTagService.buildProjectCssBundle(projectId).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/css"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"custom-tags.css\"")
                .body(css);
    }

    /**
     * カスタムタグ管理画面のプレビュー用。DB保存前のテンプレート/CSSでも、実際の投稿と同じ
     * Markdownレンダリングとセレクタプリフィックス付与を適用した結果を返す(issue #335)。
     * テスト本文中の組み込みタグ(`[blogcard URL]`、`[amazon URL]`)も、実際の投稿と同じ
     * BlogCardTagRenderService/AmazonTagRenderServiceで展開してから{{content}}へ差し込む(issue #346)。
     */
    @PostMapping("/preview")
    public CustomTagPreviewResponse preview(
            @PathVariable Long projectId, @Valid @RequestBody CustomTagPreviewRequest request) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        String testContent = blogCardTagRenderService.render(request.testContent(), projectId);
        // プレビューは特定サイトに紐付かないため、本番サイト向けの実リンクは常に非活性化する(issue #389)。
        testContent = amazonTagRenderService.render(testContent, projectId, false);
        String html = customTagRenderService.previewTemplate(request.htmlTemplate(), testContent);
        String wrappedHtml = renderedContentWrapperService.wrap(html, projectId);
        String css = customTagService.previewCss(request.cssContent(), projectId);
        return new CustomTagPreviewResponse(wrappedHtml, css);
    }
}
