package com.letsblog.content.service;

import com.letsblog.content.client.ProjectBridgeClient;
import com.letsblog.content.contentcache.ContentCacheService;
import com.letsblog.content.contentcache.ContentScrapingException;
import com.letsblog.content.dto.ContentCacheResponse;
import com.letsblog.content.dto.TagDesignColors;
import com.letsblog.content.domain.ContentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * issue #641: AmazonTagRenderServiceは非信頼なスクレイピング結果(商品名・価格等)をHTMLへ
 * 埋め込む。XSS対策のエスケープと、http/https以外のURL(javascript:等)をリンク化しないことを検証する。
 */
@ExtendWith(MockitoExtension.class)
class AmazonTagRenderServiceTest {

    @Mock
    private ContentCacheService contentCacheService;

    @Mock
    private ProjectBridgeClient projectBridgeClient;

    @Mock
    private CurrentActorService currentActorService;

    private AmazonTagRenderService service() {
        return new AmazonTagRenderService(contentCacheService, projectBridgeClient, currentActorService);
    }

    private void stubTagDesign() {
        ProjectBridgeClient.TagDesignResponse response =
                new ProjectBridgeClient.TagDesignResponse("#fff", "#000", "#f00", null, null);
        lenient().when(projectBridgeClient.resolveTagDesign(any(), anyString(), any())).thenReturn(response);
        lenient().when(projectBridgeClient.toColors(any()))
                .thenReturn(new TagDesignColors("#fff", "#000", "#f00", null));
    }

    @Test
    void render_商品名にHTMLタグを含むレスポンスはエスケープされて出力される() {
        stubTagDesign();
        Map<String, String> data = Map.of(
                "productName", "<script>alert(1)</script>",
                "productUrl", "https://amazon.co.jp/dp/xxx",
                "price", "1000");
        when(contentCacheService.resolve("https://amazon.co.jp/dp/xxx"))
                .thenReturn(new ContentCacheResponse(
                        "https://amazon.co.jp/dp/xxx", ContentType.AMAZON, data, Instant.now(), Instant.now()));

        String result = service().render("[amazon https://amazon.co.jp/dp/xxx]", 1L, true);

        assertFalse(result.contains("<script>"));
        assertTrue(result.contains("&lt;script&gt;"));
    }

    @Test
    void render_javascriptプロトコルの商品URLはリンク化されない() {
        stubTagDesign();
        Map<String, String> data = Map.of(
                "productName", "商品名",
                "productUrl", "javascript:alert(1)",
                "price", "1000");
        when(contentCacheService.resolve("https://amazon.co.jp/dp/xxx"))
                .thenReturn(new ContentCacheResponse(
                        "https://amazon.co.jp/dp/xxx", ContentType.AMAZON, data, Instant.now(), Instant.now()));

        String result = service().render("[amazon https://amazon.co.jp/dp/xxx]", 1L, true);

        // productUrlがhttp/https以外の場合、フォールバックとして呼び出し元URL(rawUrl)を使う実装のため、
        // href属性値にjavascript:が出現しないことを確認する。
        assertFalse(result.contains("href=\"javascript:"));
    }

    @Test
    void render_価格に円マークが無ければ全角円マークを補う() {
        stubTagDesign();
        Map<String, String> data = Map.of(
                "productName", "商品名",
                "productUrl", "https://amazon.co.jp/dp/xxx",
                "price", "1000");
        when(contentCacheService.resolve("https://amazon.co.jp/dp/xxx"))
                .thenReturn(new ContentCacheResponse(
                        "https://amazon.co.jp/dp/xxx", ContentType.AMAZON, data, Instant.now(), Instant.now()));

        String result = service().render("[amazon https://amazon.co.jp/dp/xxx]", 1L, true);

        assertTrue(result.contains("￥1000"));
    }

    @Test
    void render_非本番サイトでは商品リンクを非活性化する() {
        stubTagDesign();
        Map<String, String> data = Map.of(
                "productName", "商品名",
                "productUrl", "https://amazon.co.jp/dp/xxx",
                "price", "1000");
        when(contentCacheService.resolve("https://amazon.co.jp/dp/xxx"))
                .thenReturn(new ContentCacheResponse(
                        "https://amazon.co.jp/dp/xxx", ContentType.AMAZON, data, Instant.now(), Instant.now()));

        String result = service().render("[amazon https://amazon.co.jp/dp/xxx]", 1L, false);

        assertFalse(result.contains("<a "));
        assertTrue(result.contains("<div class=\"lb-amazon-card\">"));
    }

    @Test
    void render_スクレイピング失敗時は通常のリンクにフォールバックする() {
        when(contentCacheService.resolve(anyString()))
                .thenThrow(new ContentScrapingException("取得失敗", null));

        String result = service().render("[amazon https://amazon.co.jp/dp/xxx]", 1L, true);

        assertTrue(result.contains("<a href=\"https://amazon.co.jp/dp/xxx\""));
        assertFalse(result.contains("lb-amazon-card"));
    }

    /**
     * issue #760: プロジェクトに紐付いていないサイトへの公開ではprojectId=nullで呼ばれる。
     * projectIdはそのままタグデザイン解決へ渡し(受け側がprojectId=nullなら固定のデフォルト値を返す)、
     * 400/502にならずレンダリング自体は成功する。
     */
    @Test
    void render_projectIdがnullでもタグデザインを解決してカードを描画する() {
        stubTagDesign();
        Map<String, String> data = Map.of(
                "productName", "商品名",
                "productUrl", "https://amazon.co.jp/dp/xxx",
                "price", "1000");
        when(contentCacheService.resolve("https://amazon.co.jp/dp/xxx"))
                .thenReturn(new ContentCacheResponse(
                        "https://amazon.co.jp/dp/xxx", ContentType.AMAZON, data,
                        Instant.now(), Instant.now()));

        String result = service().render("[amazon https://amazon.co.jp/dp/xxx]", null, true);

        assertTrue(result.contains("lb-amazon-card"));
        assertTrue(result.contains("商品名"));
        verify(projectBridgeClient, atLeastOnce()).resolveTagDesign(isNull(), anyString(), any());
    }

    @Test
    void render_価格の取得時刻はUTCの壁時計でyyyy_MM_dd_HH_mm表記になる() {
        stubTagDesign();
        Map<String, String> data = Map.of(
                "productName", "商品名",
                "productUrl", "https://amazon.co.jp/dp/xxx",
                "price", "1000");
        Instant checked = Instant.parse("2026-09-08T20:03:35Z");
        when(contentCacheService.resolve("https://amazon.co.jp/dp/xxx"))
                .thenReturn(new ContentCacheResponse(
                        "https://amazon.co.jp/dp/xxx", ContentType.AMAZON, data, checked, checked));

        String result = service().render("[amazon https://amazon.co.jp/dp/xxx]", 1L, true);

        assertTrue(result.contains("2026/09/08 20:03時点の価格です"), result);
    }

    @Test
    void render_タグが無ければそのまま返す() {
        String markdown = "普通の本文です。";
        assertTrue(service().render(markdown, 1L, true).equals(markdown));
    }

    private void stubProduct(Map<String, String> data, Instant checked) {
        when(contentCacheService.resolve("https://amazon.co.jp/dp/xxx"))
                .thenReturn(new ContentCacheResponse(
                        "https://amazon.co.jp/dp/xxx", ContentType.AMAZON, data, checked, checked));
    }

    private static final Map<String, String> PRODUCT = Map.of(
            "productName", "商品名",
            "productUrl", "https://amazon.co.jp/dp/xxx",
            "imageUrl", "https://m.media-amazon.com/x.jpg",
            "summary", "概要",
            "price", "1000");

    // ---- issue #1563: 取得したデータを目印として残す ----

    @Test
    void render_取得したデータを目印のJSONに生の値で残し_投稿時点のHTMLを目印で挟む() {
        stubTagDesign();
        stubProduct(PRODUCT, Instant.parse("2026-09-08T20:03:35Z"));

        String result = service().render("[amazon https://amazon.co.jp/dp/xxx]", 1L, true);

        com.fasterxml.jackson.databind.JsonNode marker = EmbedMarkerTestSupport.firstMarker(result);
        assertEquals("AMAZON", marker.get("type").asText());
        com.fasterxml.jackson.databind.JsonNode data = marker.get("data");
        assertEquals("商品名", data.get("productName").asText());
        assertEquals("￥1000", data.get("price").asText());
        assertEquals("概要", data.get("summary").asText());
        assertEquals("https://amazon.co.jp/dp/xxx", data.get("productUrl").asText());
        assertEquals("https://m.media-amazon.com/x.jpg", data.get("imageUrl").asText());
        assertEquals("2026/09/08 20:03時点の価格です", data.get("priceTimestamp").asText());
        assertEquals(1, EmbedMarkerTestSupport.closeCount(result));
        assertTrue(result.indexOf("<!-- lbs:embed ") < result.indexOf("lb-amazon-card\""));
        assertTrue(result.indexOf("lb-amazon-card\"") < result.indexOf("<!-- /lbs:embed -->"));
    }

    @Test
    void render_非本番サイトでは目印の商品URLを空にする() {
        stubTagDesign();
        stubProduct(PRODUCT, Instant.now());

        String result = service().render("[amazon https://amazon.co.jp/dp/xxx]", 1L, false);

        assertEquals("", EmbedMarkerTestSupport.firstMarker(result).get("data").get("productUrl").asText());
        assertFalse(result.contains("href="));
    }

    @Test
    void render_単独の行のタグは空行で区切り_文中のタグは区切らない() {
        stubTagDesign();
        stubProduct(PRODUCT, Instant.now());

        String alone = service().render("前\n[amazon https://amazon.co.jp/dp/xxx]\n後", 1L, true);
        String inline = service().render("これ[amazon https://amazon.co.jp/dp/xxx]です", 1L, true);

        assertTrue(alone.contains("-->\n\n<a class=\"lb-amazon-card\""), alone);
        assertTrue(alone.contains("</a>\n\n<!-- /lbs:embed -->\n後"), alone);
        assertTrue(inline.contains("これ<!-- lbs:embed "), inline);
        assertTrue(inline.contains("</a><!-- /lbs:embed -->です"), inline);
    }

    @Test
    void render_目印のJSONはコメントを閉じずタグ記法として解釈されない() {
        stubTagDesign();
        stubProduct(Map.of("productName", "A --> <i>[amazon x]</i>", "productUrl", "https://amazon.co.jp/dp/xxx"),
                Instant.now());

        String result = service().render("[amazon https://amazon.co.jp/dp/xxx]", 1L, true);

        String raw = EmbedMarkerTestSupport.firstMarkerRaw(result);
        assertFalse(raw.contains("-") || raw.contains("<") || raw.contains(">") || raw.contains("[") || raw.contains("]"));
        assertEquals("A --> <i>[amazon x]</i>",
                EmbedMarkerTestSupport.firstMarker(result).get("data").get("productName").asText());
    }

    @Test
    void render_取得時刻や価格がなければ目印の価格関連は空になる() {
        stubTagDesign();
        when(contentCacheService.resolve("https://amazon.co.jp/dp/xxx"))
                .thenReturn(new ContentCacheResponse(
                        "https://amazon.co.jp/dp/xxx", ContentType.AMAZON,
                        Map.of("productName", "商品名", "productUrl", "javascript:x"), null, Instant.now()));

        String result = service().render("[amazon https://amazon.co.jp/dp/xxx]", 1L, true);

        com.fasterxml.jackson.databind.JsonNode data = EmbedMarkerTestSupport.firstMarker(result).get("data");
        assertEquals("", data.get("price").asText());
        assertEquals("", data.get("priceTimestamp").asText());
        assertEquals("https://amazon.co.jp/dp/xxx", data.get("productUrl").asText());
        assertEquals("", data.get("imageUrl").asText());
    }

    @Test
    void render_取得に失敗したリンクには目印を付けない() {
        when(contentCacheService.resolve(anyString())).thenThrow(new ContentScrapingException("取得失敗", null));

        String result = service().render("[amazon https://amazon.co.jp/dp/xxx]", 1L, true);

        assertFalse(result.contains("lbs:embed"));
    }
}
