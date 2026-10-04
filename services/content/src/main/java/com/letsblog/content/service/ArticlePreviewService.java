package com.letsblog.content.service;

import com.letsblog.content.markdown.MarkdownRenderer;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

/**
 * VSCode拡張の記事プレビュー機能向けに、Markdown→HTML変換(カスタムタグ・組み込みタグの展開含む)を
 * 行う(issue #576)。legacy-apiのArticlePreviewServiceのうち、renderHtml(記事本文のレンダリング
 * パイプライン。CMSへの依存を持たない)のみをcontent-serviceへ移設する。
 *
 * <p>サイトの実テーマCSS取得・骨格差し替え(旧プレビュー経路)は、プレビューを実サイトの署名付きURL
 * (publishing-service、issue #1561)へ一本化した issue #1564 で削除した。
 */
@Service
public class ArticlePreviewService {

    private final CustomTagRenderService customTagRenderService;
    private final BlogCardTagRenderService blogCardTagRenderService;
    private final AmazonTagRenderService amazonTagRenderService;
    private final RechartsTagRenderService rechartsTagRenderService;
    private final PlantUmlEmbedService plantUmlEmbedService;
    private final PlantUmlTagRenderService plantUmlTagRenderService;
    private final TocStyleRenderService tocStyleRenderService;
    private final RenderedContentWrapperService renderedContentWrapperService;
    private final MarkdownRenderer markdownRenderer;

    public ArticlePreviewService(
            CustomTagRenderService customTagRenderService,
            BlogCardTagRenderService blogCardTagRenderService,
            AmazonTagRenderService amazonTagRenderService,
            RechartsTagRenderService rechartsTagRenderService,
            PlantUmlEmbedService plantUmlEmbedService,
            PlantUmlTagRenderService plantUmlTagRenderService,
            TocStyleRenderService tocStyleRenderService,
            RenderedContentWrapperService renderedContentWrapperService,
            MarkdownRenderer markdownRenderer) {
        this.customTagRenderService = customTagRenderService;
        this.blogCardTagRenderService = blogCardTagRenderService;
        this.amazonTagRenderService = amazonTagRenderService;
        this.rechartsTagRenderService = rechartsTagRenderService;
        this.plantUmlEmbedService = plantUmlEmbedService;
        this.plantUmlTagRenderService = plantUmlTagRenderService;
        this.tocStyleRenderService = tocStyleRenderService;
        this.renderedContentWrapperService = renderedContentWrapperService;
        this.markdownRenderer = markdownRenderer;
    }

    /**
     * カスタムタグ展開 + 組み込みタグ展開 + Markdown→HTML変換を行う。PostPublishService(legacy-api)と
     * 違い、実際のCMSへの画像アップロードは行わない(プレビュー用の軽量処理)。PlantUML図はCMSアップロードの
     * 代わりにdata URIとして直接埋め込むことで、投稿後と同じ図としてプレビューに表示する(Issue #345)。
     *
     * [recharts]タグの記法・データが不正な場合、他の組み込みタグと異なりInvalidRechartsTagExceptionを
     * 捕捉し、以降のレンダリングを中止してエラーメッセージのみを表示する(Issue #340)。
     * [plantuml]〜[/plantuml]組み込みタグも同じ方針で、InvalidPlantUmlTagExceptionを捕捉して
     * レンダリングを中止する(Issue #344)。既存の```plantumlフェンスコードブロック記法(下の
     * plantUmlEmbedService呼び出し)とは併存し、置き換えない。
     */
    public String renderHtml(Long projectId, String markdown) {
        String rendered = customTagRenderService.render(markdown, projectId);
        rendered = blogCardTagRenderService.render(rendered, projectId);
        // プレビューは特定サイトに紐付かないため、本番サイト向けの実リンクは常に非活性化する(issue #389)。
        rendered = amazonTagRenderService.render(rendered, projectId, false);
        try {
            rendered = rechartsTagRenderService.render(rendered);
        } catch (InvalidRechartsTagException e) {
            return renderRechartsError(e.getMessage());
        }
        try {
            rendered = plantUmlTagRenderService.renderForPreview(rendered);
        } catch (InvalidPlantUmlTagException e) {
            return renderPlantUmlError(e.getMessage());
        }
        rendered = plantUmlEmbedService.embedDiagramsForPreview(rendered);
        String html = markdownRenderer.render(rendered);
        html = tocStyleRenderService.applyHtmlTemplate(html, projectId);
        return renderedContentWrapperService.wrap(html, projectId);
    }

    private String renderRechartsError(String message) {
        return "<div role=\"alert\" style=\"background:#f8d7da;color:#842029;padding:12px 16px;"
                + "border-radius:4px;font-family:sans-serif;font-size:14px;\">"
                + "<strong>チャートのレンダリングエラー:</strong> " + HtmlUtils.htmlEscape(message) + "</div>";
    }

    private String renderPlantUmlError(String message) {
        return "<div role=\"alert\" style=\"background:#f8d7da;color:#842029;padding:12px 16px;"
                + "border-radius:4px;font-family:sans-serif;font-size:14px;\">"
                + "<strong>PlantUML図のレンダリングエラー:</strong> " + HtmlUtils.htmlEscape(message) + "</div>";
    }
}
