package com.letsblog.api.service;

import com.letsblog.api.contentcache.ContentCacheService;
import com.letsblog.api.contentcache.ContentScrapingException;
import com.letsblog.api.domain.ContentType;
import com.letsblog.api.domain.EmbedTagType;
import com.letsblog.api.dto.ContentCacheResponse;
import com.letsblog.api.dto.TagDesignColors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlogCardTagRenderServiceTest {

    private static final Long PROJECT_ID = 1L;

    @Mock
    private ContentCacheService contentCacheService;

    @Mock
    private TagDesignSettingService tagDesignSettingService;

    private BlogCardTagRenderService service;

    @BeforeEach
    void setUp() {
        service = new BlogCardTagRenderService(contentCacheService, tagDesignSettingService);
        lenient().when(tagDesignSettingService.resolveColors(PROJECT_ID, EmbedTagType.BLOGCARD))
                .thenReturn(new TagDesignColors("#ffffff", "#1a1a1a", "#2563eb", null));
    }

    private ContentCacheResponse response(Map<String, String> data) {
        return new ContentCacheResponse(
                "https://example.com/", ContentType.BLOGCARD, data, LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    void render_blogcardタグをOGP情報のカードHTMLに展開する() {
        String url = "https://example.com/posts/1";
        when(contentCacheService.resolve(url)).thenReturn(response(Map.of(
                "title", "記事タイトル",
                "description", "記事の説明",
                "imageUrl", "https://example.com/eyecatch.png",
                "siteName", "サンプルブログ",
                "url", url)));

        String result = service.render("本文\n\n[blogcard " + url + "]\n\n続き", PROJECT_ID);

        assertTrue(result.contains("<style>"), "スタイルブロックが含まれること");
        assertTrue(result.contains("記事タイトル"));
        assertTrue(result.contains("記事の説明"));
        assertTrue(result.contains("サンプルブログ"));
        assertTrue(result.contains("href=\"" + url + "\""));
        assertTrue(result.contains("background-image:url('https://example.com/eyecatch.png')"));
        assertTrue(result.contains("本文"));
        assertTrue(result.contains("続き"));
    }

    @Test
    void render_customCssが設定されていれば色ベースのCSSの代わりに完全に置き換える() {
        org.mockito.Mockito.reset(tagDesignSettingService, contentCacheService);
        String url = "https://example.com/posts/1";
        when(contentCacheService.resolve(url)).thenReturn(response(Map.of("title", "記事タイトル")));
        when(tagDesignSettingService.resolveColors(PROJECT_ID, EmbedTagType.BLOGCARD))
                .thenReturn(new TagDesignColors("#ffffff", "#1a1a1a", "#2563eb", ".lb-blogcard{font-weight:bold;}"));

        String result = service.render("[blogcard " + url + "]", PROJECT_ID);

        assertTrue(result.contains(".lb-blogcard{font-weight:bold;}"));
        assertFalse(result.contains("background:#ffffff"), "色ベースの生成CSSは含まれないこと: " + result);
    }

    @Test
    void render_htmlTemplateが設定されていればプレースホルダを差し込んで展開する() {
        String url = "https://example.com/posts/1";
        when(contentCacheService.resolve(url)).thenReturn(response(Map.of(
                "title", "記事タイトル",
                "description", "記事の説明",
                "siteName", "サンプルブログ",
                "url", url)));
        when(tagDesignSettingService.resolveHtmlTemplate(PROJECT_ID, EmbedTagType.BLOGCARD))
                .thenReturn("<div class=\"custom\"><a href=\"{{url}}\">{{title}}</a><p>{{description}}({{siteName}})</p></div>");

        String result = service.render("[blogcard " + url + "]", PROJECT_ID);

        assertEquals(
                "<div class=\"custom\"><a href=\"" + url + "\">記事タイトル</a><p>記事の説明(サンプルブログ)</p></div>",
                result);
    }

    @Test
    void render_タグがなければ何も変更せずスタイルブロックも付与しない() {
        String markdown = "普通の本文です。";

        String result = service.render(markdown, PROJECT_ID);

        assertEquals(markdown, result);
    }

    @Test
    void render_複数のblogcardタグをそれぞれ展開しスタイルブロックは1回だけ付与する() {
        String url1 = "https://example.com/a";
        String url2 = "https://example.com/b";
        when(contentCacheService.resolve(url1)).thenReturn(response(Map.of("title", "記事A")));
        when(contentCacheService.resolve(url2)).thenReturn(response(Map.of("title", "記事B")));

        String result = service.render("[blogcard " + url1 + "]\n\n[blogcard " + url2 + "]", PROJECT_ID);

        assertTrue(result.contains("記事A"));
        assertTrue(result.contains("記事B"));
        assertEquals(1, countOccurrences(result, "<style>"));
    }

    @Test
    void render_スクレイピング失敗時は通常のリンクにフォールバックする() {
        String url = "https://example.com/posts/1";
        when(contentCacheService.resolve(url)).thenThrow(new ContentScrapingException("失敗", new RuntimeException()));

        String result = service.render("[blogcard " + url + "]", PROJECT_ID);

        assertEquals("<a href=\"" + url + "\" target=\"_blank\" rel=\"noopener noreferrer\">" + url + "</a>", result);
        assertFalse(result.contains("<style>"), "カードが使われない場合は不要なCSSを混入させないこと");
    }

    @Test
    void render_無効なURLはリンク化せずプレーンテキストで出力する() {
        String url = "javascript:alert(1)";
        when(contentCacheService.resolve(url)).thenThrow(new IllegalArgumentException("不正なURL"));

        String result = service.render("[blogcard " + url + "]", PROJECT_ID);

        assertFalse(result.contains("<a "), "javascript:等はリンク化されないこと: " + result);
        assertFalse(result.contains("javascript:alert(1)\""), "属性値として埋め込まれないこと: " + result);
    }

    @Test
    void render_スクレイピング結果のタイトル等はHTMLエスケープされる() {
        String url = "https://example.com/posts/1";
        when(contentCacheService.resolve(url)).thenReturn(response(Map.of(
                "title", "<script>alert(1)</script>",
                "description", "\"onmouseover=\"alert(1)")));

        String result = service.render("[blogcard " + url + "]", PROJECT_ID);

        assertFalse(result.contains("<script>alert(1)</script>"), "scriptタグがエスケープされずに出力されないこと: " + result);
        assertTrue(result.contains("&lt;script&gt;"));
    }

    @Test
    void render_og_urlがjavascriptプロトコルの場合は入力URLにフォールバックする() {
        String url = "https://example.com/posts/1";
        when(contentCacheService.resolve(url)).thenReturn(response(Map.of(
                "title", "記事タイトル",
                "url", "javascript:alert(1)")));

        String result = service.render("[blogcard " + url + "]", PROJECT_ID);

        assertTrue(result.contains("href=\"" + url + "\""));
        assertFalse(result.contains("javascript:alert(1)"));
    }

    @Test
    void render_og_imageがhttp以外の場合はサムネイルを表示しない() {
        String url = "https://example.com/posts/1";
        when(contentCacheService.resolve(url)).thenReturn(response(Map.of(
                "title", "記事タイトル",
                "imageUrl", "data:text/html,<script>alert(1)</script>")));

        String result = service.render("[blogcard " + url + "]", PROJECT_ID);

        assertFalse(result.contains("class=\"lb-blogcard-thumb\""),
                "無効な画像URLはサムネイル要素自体を出さないこと(CSS定義自体は含まれてよい): " + result);
    }

    @Test
    void render_nullとから文字列はそのまま返す() {
        assertEquals(null, service.render(null, PROJECT_ID));
        assertEquals("", service.render("", PROJECT_ID));
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
