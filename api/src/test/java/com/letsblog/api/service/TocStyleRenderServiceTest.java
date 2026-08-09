package com.letsblog.api.service;

import com.letsblog.api.domain.EmbedTagType;
import com.letsblog.api.dto.TagDesignColors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TocStyleRenderServiceTest {

    private static final Long PROJECT_ID = 1L;

    @Mock
    private TagDesignSettingService tagDesignSettingService;

    private TocStyleRenderService service;

    @BeforeEach
    void setUp() {
        service = new TocStyleRenderService(tagDesignSettingService);
        lenient().when(tagDesignSettingService.resolveColors(PROJECT_ID, EmbedTagType.TOC))
                .thenReturn(new TagDesignColors("#1f2937", "#f3f4f6", "#60a5fa", null));
    }

    @Test
    void render_toc組み込みタグを含む場合はカスタムデザインのCSSを注入する() {
        String markdown = "# タイトル\n\n[toc]\n\n## セクション1\n\n本文";

        String result = service.render(markdown, PROJECT_ID);

        assertTrue(result.contains("<style>"));
        assertTrue(result.contains(".lb-toc-list{"));
        assertTrue(result.contains("background:#1f2937"));
        assertTrue(result.contains("color:#f3f4f6"));
        assertTrue(result.contains("color:#60a5fa"));
        assertTrue(result.contains("[toc]"), "元のMarkdown本文は保持されること");
    }

    @Test
    void render_customCssが設定されていれば色ベースのCSSの後ろに連結する() {
        org.mockito.Mockito.reset(tagDesignSettingService);
        when(tagDesignSettingService.resolveColors(PROJECT_ID, EmbedTagType.TOC))
                .thenReturn(new TagDesignColors("#1f2937", "#f3f4f6", "#60a5fa", ".lb-toc-list{font-weight:bold;}"));

        String result = service.render("[toc]\n\n## セクション1\n\n本文", PROJECT_ID);

        assertTrue(result.contains(".lb-toc-list{font-weight:bold;}"));
    }

    @Test
    void render_大文字TOCでもflexmarkと同様に検出しCSSを注入する() {
        String markdown = "[TOC]\n\n## セクション1\n\n本文";

        String result = service.render(markdown, PROJECT_ID);

        assertTrue(result.contains("<style>"));
    }

    @Test
    void render_toc組み込みタグを含まない場合は何もしない() {
        String markdown = "# タイトル\n\n本文だけです。";

        String result = service.render(markdown, PROJECT_ID);

        assertEquals(markdown, result);
    }

    @Test
    void render_見出し中のtocという単語だけでは反応しない() {
        String markdown = "## tocについて説明します\n\n本文";

        String result = service.render(markdown, PROJECT_ID);

        assertEquals(markdown, result);
    }

    @Test
    void render_nullとから文字列はそのまま返す() {
        assertEquals(null, service.render(null, PROJECT_ID));
        assertEquals("", service.render("", PROJECT_ID));
    }

    @Test
    void render_タグがなければデザイン設定サービスを呼ばない() {
        service.render("普通の本文", PROJECT_ID);

        org.mockito.Mockito.verify(tagDesignSettingService, org.mockito.Mockito.never())
                .resolveColors(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void applyHtmlTemplate_未設定ならhtmlをそのまま返す() {
        String html = "<ul class=\"lb-toc-list\"><li><a href=\"#a\">a</a></li></ul>";

        String result = service.applyHtmlTemplate(html, PROJECT_ID);

        assertEquals(html, result);
    }

    @Test
    void applyHtmlTemplate_設定されていれば目次全体をtocプレースホルダに差し込む() {
        when(tagDesignSettingService.resolveHtmlTemplate(PROJECT_ID, EmbedTagType.TOC))
                .thenReturn("<details><summary>目次</summary>{{toc}}</details>");
        String toc = "<ul class=\"lb-toc-list\"><li><a href=\"#a\">a</a></li></ul>";
        String html = "<h1>タイトル</h1>\n" + toc + "\n<h2 id=\"a\">a</h2>";

        String result = service.applyHtmlTemplate(html, PROJECT_ID);

        assertEquals("<h1>タイトル</h1>\n<details><summary>目次</summary>" + toc + "</details>\n<h2 id=\"a\">a</h2>", result);
    }

    @Test
    void applyHtmlTemplate_見出し階層による入れ子ulも1ブロックとして丸ごと差し込む() {
        when(tagDesignSettingService.resolveHtmlTemplate(PROJECT_ID, EmbedTagType.TOC))
                .thenReturn("<nav>{{toc}}</nav>");
        String toc = "<ul class=\"lb-toc-list\">\n"
                + "<li><a href=\"#a\">a</a>\n<ul>\n<li><a href=\"#a-1\">a-1</a></li>\n</ul>\n</li>\n"
                + "<li><a href=\"#b\">b</a></li>\n"
                + "</ul>";
        String html = "<h1>タイトル</h1>\n" + toc + "\n<h2 id=\"a\">a</h2>";

        String result = service.applyHtmlTemplate(html, PROJECT_ID);

        assertEquals("<h1>タイトル</h1>\n<nav>" + toc + "</nav>\n<h2 id=\"a\">a</h2>", result);
    }

    @Test
    void applyHtmlTemplate_目次自体がなければ何もしない() {
        when(tagDesignSettingService.resolveHtmlTemplate(PROJECT_ID, EmbedTagType.TOC))
                .thenReturn("<nav>{{toc}}</nav>");
        String html = "<h1>タイトル</h1>\n<p>本文</p>";

        String result = service.applyHtmlTemplate(html, PROJECT_ID);

        assertEquals(html, result);
    }

    @Test
    void applyHtmlTemplate_nullとから文字列はそのまま返す() {
        assertEquals(null, service.applyHtmlTemplate(null, PROJECT_ID));
        assertEquals("", service.applyHtmlTemplate("", PROJECT_ID));
    }
}
