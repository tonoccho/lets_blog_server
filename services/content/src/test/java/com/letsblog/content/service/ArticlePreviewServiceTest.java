package com.letsblog.content.service;

import com.letsblog.content.markdown.MarkdownRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * legacy-apiのArticlePreviewServiceTestのうち、content-serviceへ移設した記事本文レンダリング
 * (renderHtml)の振る舞いを引き継いだテスト(issue #576)。
 */
@ExtendWith(MockitoExtension.class)
class ArticlePreviewServiceTest {

    @Mock
    private CustomTagRenderService customTagRenderService;

    @Mock
    private BlogCardTagRenderService blogCardTagRenderService;

    @Mock
    private AmazonTagRenderService amazonTagRenderService;

    @Mock
    private RechartsTagRenderService rechartsTagRenderService;

    @Mock
    private PlantUmlEmbedService plantUmlEmbedService;

    @Mock
    private PlantUmlTagRenderService plantUmlTagRenderService;

    @Mock
    private TocStyleRenderService tocStyleRenderService;

    @Mock
    private RenderedContentWrapperService renderedContentWrapperService;

    @Mock
    private MarkdownRenderer markdownRenderer;

    private ArticlePreviewService service;

    @BeforeEach
    void setUp() {
        service = new ArticlePreviewService(
                customTagRenderService, blogCardTagRenderService, amazonTagRenderService, rechartsTagRenderService,
                plantUmlEmbedService, plantUmlTagRenderService, tocStyleRenderService, renderedContentWrapperService,
                markdownRenderer);
        // renderHtml()は必ずrechartsTagRenderService/plantUmlTagRenderService/plantUmlEmbedServiceを
        // 経由するため、それら自体を検証しないテストでは素通しにしておく
        // (未スタブだとnullが返り、以降の呼び出しの引数が狂うため)。
        lenient().when(rechartsTagRenderService.render(anyString())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(plantUmlTagRenderService.renderForPreview(anyString())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(plantUmlEmbedService.embedDiagramsForPreview(anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void renderHtml_カスタムタグ展開後にMarkdownをHTML変換する() {
        when(customTagRenderService.render("**bold**", 1L)).thenReturn("**bold** rendered");
        when(blogCardTagRenderService.render("**bold** rendered", 1L)).thenReturn("**bold** rendered");
        when(amazonTagRenderService.render("**bold** rendered", 1L, false)).thenReturn("**bold** rendered");
        when(markdownRenderer.render("**bold** rendered")).thenReturn("<p><strong>bold</strong> rendered</p>");
        when(tocStyleRenderService.applyHtmlTemplate("<p><strong>bold</strong> rendered</p>", 1L))
                .thenReturn("<p><strong>bold</strong> rendered</p>");
        when(renderedContentWrapperService.wrap("<p><strong>bold</strong> rendered</p>", 1L))
                .thenReturn("<div class=\"lets-blog-rendered\"><p><strong>bold</strong> rendered</p></div>");

        String html = service.renderHtml(1L, "**bold**");

        assertEquals("<div class=\"lets-blog-rendered\"><p><strong>bold</strong> rendered</p></div>", html);
        verify(customTagRenderService).render("**bold**", 1L);
        verify(blogCardTagRenderService).render("**bold** rendered", 1L);
        verify(amazonTagRenderService).render("**bold** rendered", 1L, false);
        verify(markdownRenderer).render("**bold** rendered");
        verify(tocStyleRenderService).applyHtmlTemplate("<p><strong>bold</strong> rendered</p>", 1L);
        verify(renderedContentWrapperService).wrap("<p><strong>bold</strong> rendered</p>", 1L);
    }

    @Test
    void renderHtml_rechartsタグが不正な場合はレンダリングを中止してエラーメッセージを返す() {
        when(customTagRenderService.render("markdown", 1L)).thenReturn("markdown");
        when(blogCardTagRenderService.render("markdown", 1L)).thenReturn("markdown");
        when(amazonTagRenderService.render("markdown", 1L, false)).thenReturn("markdown");
        when(rechartsTagRenderService.render("markdown"))
                .thenThrow(new InvalidRechartsTagException("type属性は必須です"));

        String html = service.renderHtml(1L, "markdown");

        assertTrue(html.contains("type属性は必須です"));
        verifyNoInteractions(plantUmlTagRenderService, plantUmlEmbedService, markdownRenderer, tocStyleRenderService,
                renderedContentWrapperService);
    }

    @Test
    void renderHtml_recharts展開後の内容がMarkdown変換される() {
        when(customTagRenderService.render("markdown", 1L)).thenReturn("markdown");
        when(blogCardTagRenderService.render("markdown", 1L)).thenReturn("markdown");
        when(amazonTagRenderService.render("markdown", 1L, false)).thenReturn("markdown");
        when(rechartsTagRenderService.render("markdown")).thenReturn("markdown<div>chart</div>");
        when(markdownRenderer.render("markdown<div>chart</div>")).thenReturn("<p>markdown</p><div>chart</div>");
        when(tocStyleRenderService.applyHtmlTemplate("<p>markdown</p><div>chart</div>", 1L))
                .thenReturn("<p>markdown</p><div>chart</div>");
        when(renderedContentWrapperService.wrap("<p>markdown</p><div>chart</div>", 1L))
                .thenReturn("<div class=\"lets-blog-rendered\"><p>markdown</p><div>chart</div></div>");

        String html = service.renderHtml(1L, "markdown");

        assertEquals("<div class=\"lets-blog-rendered\"><p>markdown</p><div>chart</div></div>", html);
    }

    @Test
    void renderHtml_plantumlフェンスをdataURI画像へ差し替えてからMarkdown変換する() {
        when(customTagRenderService.render("```plantuml\n@startuml\n@enduml\n```", 1L))
                .thenReturn("```plantuml\n@startuml\n@enduml\n```");
        when(blogCardTagRenderService.render("```plantuml\n@startuml\n@enduml\n```", 1L))
                .thenReturn("```plantuml\n@startuml\n@enduml\n```");
        when(amazonTagRenderService.render("```plantuml\n@startuml\n@enduml\n```", 1L, false))
                .thenReturn("```plantuml\n@startuml\n@enduml\n```");
        when(plantUmlEmbedService.embedDiagramsForPreview("```plantuml\n@startuml\n@enduml\n```"))
                .thenReturn("![diagram](data:image/png;base64,AAAA)");
        when(markdownRenderer.render("![diagram](data:image/png;base64,AAAA)"))
                .thenReturn("<img src=\"data:image/png;base64,AAAA\">");
        when(tocStyleRenderService.applyHtmlTemplate("<img src=\"data:image/png;base64,AAAA\">", 1L))
                .thenReturn("<img src=\"data:image/png;base64,AAAA\">");
        when(renderedContentWrapperService.wrap("<img src=\"data:image/png;base64,AAAA\">", 1L))
                .thenReturn("<div class=\"lets-blog-rendered\"><img src=\"data:image/png;base64,AAAA\"></div>");

        String html = service.renderHtml(1L, "```plantuml\n@startuml\n@enduml\n```");

        assertEquals("<div class=\"lets-blog-rendered\"><img src=\"data:image/png;base64,AAAA\"></div>", html);
        verify(plantUmlEmbedService).embedDiagramsForPreview("```plantuml\n@startuml\n@enduml\n```");
    }

    @Test
    void renderHtml_plantumlタグをdataURI画像へ差し替えてからMarkdown変換する() {
        String markdown = "[plantuml]\nA->B\n[/plantuml]";
        when(customTagRenderService.render(markdown, 1L)).thenReturn(markdown);
        when(blogCardTagRenderService.render(markdown, 1L)).thenReturn(markdown);
        when(amazonTagRenderService.render(markdown, 1L, false)).thenReturn(markdown);
        when(plantUmlTagRenderService.renderForPreview(markdown))
                .thenReturn("![diagram](data:image/png;base64,AAAA)");
        when(markdownRenderer.render("![diagram](data:image/png;base64,AAAA)"))
                .thenReturn("<img src=\"data:image/png;base64,AAAA\">");
        when(tocStyleRenderService.applyHtmlTemplate("<img src=\"data:image/png;base64,AAAA\">", 1L))
                .thenReturn("<img src=\"data:image/png;base64,AAAA\">");
        when(renderedContentWrapperService.wrap("<img src=\"data:image/png;base64,AAAA\">", 1L))
                .thenReturn("<div class=\"lets-blog-rendered\"><img src=\"data:image/png;base64,AAAA\"></div>");

        String html = service.renderHtml(1L, markdown);

        assertEquals("<div class=\"lets-blog-rendered\"><img src=\"data:image/png;base64,AAAA\"></div>", html);
        verify(plantUmlTagRenderService).renderForPreview(markdown);
    }

    @Test
    void renderHtml_plantumlタグが不正な場合はレンダリングを中止してエラーメッセージを返す() {
        when(customTagRenderService.render("markdown", 1L)).thenReturn("markdown");
        when(blogCardTagRenderService.render("markdown", 1L)).thenReturn("markdown");
        when(amazonTagRenderService.render("markdown", 1L, false)).thenReturn("markdown");
        when(plantUmlTagRenderService.renderForPreview("markdown"))
                .thenThrow(new InvalidPlantUmlTagException("PlantUML図のレンダリングに失敗しました"));

        String html = service.renderHtml(1L, "markdown");

        assertTrue(html.contains("PlantUML図のレンダリングに失敗しました"));
        verifyNoInteractions(plantUmlEmbedService, markdownRenderer, tocStyleRenderService,
                renderedContentWrapperService);
    }
}
