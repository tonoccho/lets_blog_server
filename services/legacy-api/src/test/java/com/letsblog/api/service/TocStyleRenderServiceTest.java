package com.letsblog.api.service;

import com.letsblog.api.domain.EmbedTagType;
import com.letsblog.api.dto.TagDesignColors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    }

    @Test
    void buildStyle_色ベースのCSSを組み立てる() {
        TagDesignColors colors = new TagDesignColors("#1f2937", "#f3f4f6", "#60a5fa", null);

        String css = service.buildStyle(colors);

        assertTrue(css.contains(".lb-toc-list{"));
        assertTrue(css.contains("background:#1f2937"));
        assertTrue(css.contains("color:#f3f4f6"));
        assertTrue(css.contains("color:#60a5fa"));
    }

    @Test
    void buildStyle_customCssが設定されていれば色ベースのCSSの代わりに完全に置き換える() {
        TagDesignColors colors =
                new TagDesignColors("#1f2937", "#f3f4f6", "#60a5fa", ".lb-toc-list{font-weight:bold;}");

        String css = service.buildStyle(colors);

        assertEquals(".lb-toc-list{font-weight:bold;}", css);
        assertFalse(css.contains("background:#1f2937"), "色ベースの生成CSSは含まれないこと: " + css);
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
