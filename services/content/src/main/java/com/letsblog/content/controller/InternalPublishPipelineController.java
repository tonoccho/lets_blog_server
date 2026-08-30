package com.letsblog.content.controller;

import com.letsblog.content.dto.FinalizeHtmlRequest;
import com.letsblog.content.dto.HtmlResponse;
import com.letsblog.content.dto.MarkdownResponse;
import com.letsblog.content.dto.PreImageRenderRequest;
import com.letsblog.content.markdown.MarkdownRenderer;
import com.letsblog.content.service.AmazonTagRenderService;
import com.letsblog.content.service.BlogCardTagRenderService;
import com.letsblog.content.service.CustomTagRenderService;
import com.letsblog.content.service.RechartsTagRenderService;
import com.letsblog.content.service.RenderedContentWrapperService;
import com.letsblog.content.service.TocStyleRenderService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * legacy-api側のPostPublishService(issue #575でpublishing-serviceへ移設されるまで引き続きlegacy-api
 * に残る)向けの内部ブリッジ(issue #576)。公開パイプラインは元々
 * カスタムタグ→blogcard→amazon→recharts→(CMS画像アップロード。plantuml埋め込み含む)→
 * markdown→html→toc→統合CSSラッパー、という順で1つのメソッド(PostPublishService#publish)内で
 * 直列に実行されていたが、レンダリング系クラス(CustomTagRenderService等)の所有権がcontent-service
 * (posts/custom_tags等、ADR-0004のスキーマ分離)へ移ったため、CMS画像アップロードを挟む前後の
 * 2段階に分けてlegacy-apiから同期的に呼び出してもらう構成にした。
 *
 * <p>[plantuml]/```plantumlの埋め込み(CMSメディアライブラリへのアップロードを伴う)は、
 * CmsAdapter/CmsCredentialsへの依存が強いためlegacy-api側に残しており(issue #575の対象)、
 * この2段階の間でlegacy-api自身が実行する。
 */
@RestController
public class InternalPublishPipelineController {

    private final CustomTagRenderService customTagRenderService;
    private final BlogCardTagRenderService blogCardTagRenderService;
    private final AmazonTagRenderService amazonTagRenderService;
    private final RechartsTagRenderService rechartsTagRenderService;
    private final MarkdownRenderer markdownRenderer;
    private final TocStyleRenderService tocStyleRenderService;
    private final RenderedContentWrapperService renderedContentWrapperService;

    public InternalPublishPipelineController(
            CustomTagRenderService customTagRenderService,
            BlogCardTagRenderService blogCardTagRenderService,
            AmazonTagRenderService amazonTagRenderService,
            RechartsTagRenderService rechartsTagRenderService,
            MarkdownRenderer markdownRenderer,
            TocStyleRenderService tocStyleRenderService,
            RenderedContentWrapperService renderedContentWrapperService) {
        this.customTagRenderService = customTagRenderService;
        this.blogCardTagRenderService = blogCardTagRenderService;
        this.amazonTagRenderService = amazonTagRenderService;
        this.rechartsTagRenderService = rechartsTagRenderService;
        this.markdownRenderer = markdownRenderer;
        this.tocStyleRenderService = tocStyleRenderService;
        this.renderedContentWrapperService = renderedContentWrapperService;
    }

    /**
     * カスタムタグ→[blogcard]→[amazon]→[recharts]の順で展開する(legacy-apiのPostPublishService#publish
     * の元のステップ1〜4と同じ)。[recharts]タグの記法・データが不正な場合はInvalidRechartsTagExceptionを
     * 未捕捉のまま伝播させ、GlobalExceptionHandlerが400として返す(呼び出し元のlegacy-apiは、この
     * ステータスを見て投稿自体を拒否する。issue #340と同じ挙動)。
     */
    @PostMapping("/api/internal/content/render/pre-image")
    public MarkdownResponse renderPreImage(@RequestBody PreImageRenderRequest request) {
        String markdown = customTagRenderService.render(request.markdown(), request.projectId());
        markdown = blogCardTagRenderService.render(markdown, request.projectId());
        markdown = amazonTagRenderService.render(markdown, request.projectId(), request.productionSite());
        markdown = rechartsTagRenderService.render(markdown);
        return new MarkdownResponse(markdown);
    }

    /**
     * Markdown→HTML変換 + [toc]カスタムHTMLテンプレート適用 + 統合CSSラッパー適用(legacy-apiの
     * PostPublishService#publishの元のステップ9〜11と同じ)。CMS画像アップロード(plantuml埋め込み・
     * 画像参照差し替え)が完了した後のmarkdownを渡してもらう。
     */
    @PostMapping("/api/internal/content/render/finalize-html")
    public HtmlResponse finalizeHtml(@RequestBody FinalizeHtmlRequest request) {
        String html = markdownRenderer.render(request.markdown());
        html = tocStyleRenderService.applyHtmlTemplate(html, request.projectId());
        html = renderedContentWrapperService.wrap(html, request.projectId());
        return new HtmlResponse(html);
    }
}
