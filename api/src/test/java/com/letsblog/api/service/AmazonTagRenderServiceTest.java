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
class AmazonTagRenderServiceTest {

    private static final Long PROJECT_ID = 1L;

    @Mock
    private ContentCacheService contentCacheService;

    @Mock
    private TagDesignSettingService tagDesignSettingService;

    private AmazonTagRenderService service;

    @BeforeEach
    void setUp() {
        service = new AmazonTagRenderService(contentCacheService, tagDesignSettingService);
        lenient().when(tagDesignSettingService.resolveColors(PROJECT_ID, EmbedTagType.AMAZON))
                .thenReturn(new TagDesignColors("#ffffff", "#1a1a1a", "#2563eb"));
    }

    private ContentCacheResponse response(Map<String, String> data) {
        return new ContentCacheResponse(
                "https://www.amazon.co.jp/dp/B000000000", ContentType.AMAZON, data,
                LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    void render_amazonタグを商品広告カードHTMLに展開する() {
        String url = "https://www.amazon.co.jp/dp/B000000000";
        when(contentCacheService.resolve(url)).thenReturn(response(Map.of(
                "productName", "サンプル商品",
                "imageUrl", "https://m.media-amazon.com/images/large.jpg",
                "price", "￥1,980",
                "productUrl", url)));

        String result = service.render("本文\n\n[amazon " + url + "]\n\n続き", PROJECT_ID);

        assertTrue(result.contains("<style>"), "スタイルブロックが含まれること");
        assertTrue(result.contains("サンプル商品"));
        assertTrue(result.contains("￥1,980"));
        assertTrue(result.contains("href=\"" + url + "\""));
        assertTrue(result.contains("background-image:url('https://m.media-amazon.com/images/large.jpg')"));
        assertTrue(result.contains("rel=\"noopener noreferrer nofollow sponsored\""));
        assertTrue(result.contains("本文"));
        assertTrue(result.contains("続き"));
    }

    @Test
    void render_タグがなければ何も変更せずスタイルブロックも付与しない() {
        String markdown = "普通の本文です。";

        String result = service.render(markdown, PROJECT_ID);

        assertEquals(markdown, result);
    }

    @Test
    void render_価格が取得できない場合は価格要素自体を出さない() {
        String url = "https://www.amazon.co.jp/dp/B000000000";
        when(contentCacheService.resolve(url)).thenReturn(response(Map.of("productName", "サンプル商品")));

        String result = service.render("[amazon " + url + "]", PROJECT_ID);

        assertFalse(result.contains("class=\"lb-amazon-card-price\""));
    }

    @Test
    void render_複数のamazonタグをそれぞれ展開しスタイルブロックは1回だけ付与する() {
        String url1 = "https://www.amazon.co.jp/dp/AAAAAAAAAA";
        String url2 = "https://www.amazon.co.jp/dp/BBBBBBBBBB";
        when(contentCacheService.resolve(url1)).thenReturn(response(Map.of("productName", "商品A")));
        when(contentCacheService.resolve(url2)).thenReturn(response(Map.of("productName", "商品B")));

        String result = service.render("[amazon " + url1 + "]\n\n[amazon " + url2 + "]", PROJECT_ID);

        assertTrue(result.contains("商品A"));
        assertTrue(result.contains("商品B"));
        assertEquals(1, countOccurrences(result, "<style>"));
    }

    @Test
    void render_スクレイピング失敗時は通常のリンクにフォールバックする() {
        String url = "https://www.amazon.co.jp/dp/B000000000";
        when(contentCacheService.resolve(url)).thenThrow(new ContentScrapingException("失敗", new RuntimeException()));

        String result = service.render("[amazon " + url + "]", PROJECT_ID);

        assertEquals("<a href=\"" + url + "\" target=\"_blank\" rel=\"noopener noreferrer nofollow sponsored\">"
                + url + "</a>", result);
        assertFalse(result.contains("<style>"), "カードが使われない場合は不要なCSSを混入させないこと");
    }

    @Test
    void render_無効なURLはリンク化せずプレーンテキストで出力する() {
        String url = "javascript:alert(1)";
        when(contentCacheService.resolve(url)).thenThrow(new IllegalArgumentException("不正なURL"));

        String result = service.render("[amazon " + url + "]", PROJECT_ID);

        assertFalse(result.contains("<a "), "javascript:等はリンク化されないこと: " + result);
        assertFalse(result.contains("javascript:alert(1)\""), "属性値として埋め込まれないこと: " + result);
    }

    @Test
    void render_スクレイピング結果の商品名等はHTMLエスケープされる() {
        String url = "https://www.amazon.co.jp/dp/B000000000";
        when(contentCacheService.resolve(url)).thenReturn(response(Map.of(
                "productName", "<script>alert(1)</script>",
                "price", "\"onmouseover=\"alert(1)")));

        String result = service.render("[amazon " + url + "]", PROJECT_ID);

        assertFalse(result.contains("<script>alert(1)</script>"), "scriptタグがエスケープされずに出力されないこと: " + result);
        assertTrue(result.contains("&lt;script&gt;"));
    }

    @Test
    void render_productUrlがjavascriptプロトコルの場合は入力URLにフォールバックする() {
        String url = "https://www.amazon.co.jp/dp/B000000000";
        when(contentCacheService.resolve(url)).thenReturn(response(Map.of(
                "productName", "サンプル商品",
                "productUrl", "javascript:alert(1)")));

        String result = service.render("[amazon " + url + "]", PROJECT_ID);

        assertTrue(result.contains("href=\"" + url + "\""));
        assertFalse(result.contains("javascript:alert(1)"));
    }

    @Test
    void render_imageUrlがhttp以外の場合はサムネイルを表示しない() {
        String url = "https://www.amazon.co.jp/dp/B000000000";
        when(contentCacheService.resolve(url)).thenReturn(response(Map.of(
                "productName", "サンプル商品",
                "imageUrl", "data:text/html,<script>alert(1)</script>")));

        String result = service.render("[amazon " + url + "]", PROJECT_ID);

        assertFalse(result.contains("class=\"lb-amazon-card-thumb\""),
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
