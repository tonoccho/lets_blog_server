package com.letsblog.content.service;

import com.letsblog.content.domain.ContentType;
import com.letsblog.content.testsupport.ContentWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #641: AmazonTagRenderServiceは非信頼なスクレイピング結果(商品名・価格等)をHTMLへ
 * 埋め込む。XSS対策のエスケープと、http/https以外のURL(javascript:等)をリンク化しないことを検証する。
 *
 * <p>issue #1821: ContentCacheService・Amazon商品ページのパーサー・ProjectBridgeClient・CurrentActorServiceは実物。
 * 取得済みの内容はキャッシュ行(DB)として置き、スクレイピングするページはPlaywrightのBrowser(外部プロセス)のモックが返す。
 * project-serviceはHTTPフェイク。
 */
class AmazonTagRenderServiceTest {

    private static final String URL = "https://amazon.co.jp/dp/xxx";
    private static final String TAG_DESIGN_PATH = "/api/internal/project/tag-design/AMAZON";

    private ContentWorld world;

    @BeforeEach
    void setUp() {
        world = new ContentWorld().actingAsAdmin(1L);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private AmazonTagRenderService service() {
        return world.amazonTagRenderService;
    }

    private void cached(Map<String, String> data) {
        world.cached(URL, ContentType.AMAZON, data, LocalDateTime.now());
    }

    private static final Map<String, String> PRODUCT = Map.of(
            "productName", "商品名",
            "productUrl", URL,
            "imageUrl", "https://m.media-amazon.com/x.jpg",
            "summary", "概要",
            "price", "1000");

    private static Map<String, String> product(String key, String value) {
        Map<String, String> data = new HashMap<>(PRODUCT);
        data.put(key, value);
        return data;
    }

    @Test
    void render_商品名にHTMLタグを含むレスポンスはエスケープされて出力される() {
        cached(product("productName", "<script>alert(1)</script>"));

        String result = service().render("[amazon " + URL + "]", 1L, true);

        assertFalse(result.contains("<script>"));
        assertTrue(result.contains("&lt;script&gt;"));
    }

    @Test
    void render_javascriptプロトコルの商品URLはリンク化されない() {
        cached(product("productUrl", "javascript:alert(1)"));

        String result = service().render("[amazon " + URL + "]", 1L, true);

        // productUrlがhttp/https以外の場合、フォールバックとして呼び出し元URL(rawUrl)を使う実装のため、
        // href属性値にjavascript:が出現しないことを確認する。
        assertFalse(result.contains("href=\"javascript:"));
        assertTrue(result.contains("href=\"" + URL + "\""));
    }

    @Test
    void render_価格に円マークが無ければ全角円マークを補う() {
        cached(PRODUCT);

        String result = service().render("[amazon " + URL + "]", 1L, true);

        assertTrue(result.contains("￥1000"));
    }

    @Test
    void render_価格が円マークで始まるか円で終わるならそのまま使う() {
        for (String price : new String[] {"¥1,000", "￥2,000", "3,000円"}) {
            cached(product("price", price));

            String result = service().render("[amazon " + URL + "]", 1L, true);

            assertEquals(price, EmbedMarkerTestSupport.firstMarker(result).get("data").get("price").asText());
        }
    }

    @Test
    void render_価格が空なら価格も時点の表記も出さない() {
        cached(product("price", ""));

        String result = service().render("[amazon " + URL + "]", 1L, true);

        assertFalse(result.contains("<span class=\"lb-amazon-card-price\""), result);
        assertFalse(result.contains("<span class=\"lb-amazon-card-timestamp\""), result);
        assertEquals("", EmbedMarkerTestSupport.firstMarker(result).get("data").get("priceTimestamp").asText());
    }

    @Test
    void render_非本番サイトでは商品リンクを非活性化する() {
        cached(PRODUCT);

        String result = service().render("[amazon " + URL + "]", 1L, false);

        assertFalse(result.contains("<a "));
        assertTrue(result.contains("<div class=\"lb-amazon-card\"><span class=\"lb-amazon-card-"), result);
        assertFalse(result.contains("<div class=\"lb-amazon-card-"), result);
    }

    @Test
    void render_スクレイピング失敗時は通常のリンクにフォールバックする() {
        // ブラウザがページを開けない(登録の無いURL)
        String result = service().render("[amazon " + URL + "]", 1L, true);

        assertTrue(result.contains("<a href=\"" + URL + "\""));
        assertTrue(result.contains("rel=\"noopener noreferrer nofollow sponsored\""));
        assertFalse(result.contains("lb-amazon-card"));
    }

    @Test
    void render_スクレイピング失敗時に非本番サイトならリンク化しない() {
        String result = service().render("[amazon " + URL + "]", 1L, false);

        assertEquals(URL, result);
    }

    @Test
    void render_非httpスキームの取得失敗は本番サイトでもリンク化しない() {
        String result = service().render("[amazon javascript:alert(1)]", 1L, true);

        assertFalse(result.contains("<a "));
        assertTrue(result.contains("javascript:alert(1)"));
    }

    /**
     * issue #760: プロジェクトに紐付いていないサイトへの公開ではprojectId=nullで呼ばれる。
     * projectIdはそのままタグデザイン解決へ渡し(受け側がprojectId=nullなら固定のデフォルト値を返す)、
     * 400/502にならずレンダリング自体は成功する。
     */
    @Test
    void render_projectIdがnullでもタグデザインを解決してカードを描画する() {
        cached(PRODUCT);

        String result = service().render("[amazon " + URL + "]", null, true);

        assertTrue(result.contains("lb-amazon-card"));
        assertTrue(result.contains("商品名"));
        assertEquals("projectId=", world.http.requests("GET", TAG_DESIGN_PATH).get(0).query());
    }

    @Test
    void render_スタイルはデザインのcustomCssがあればそれを使い_無ければ色から組み立てる() {
        cached(PRODUCT);
        String generated = service().render("[amazon " + URL + "]", 1L, true);
        world.tagDesign("AMAZON", "  .mine{color:red}  ", null);
        String custom = service().render("[amazon " + URL + "]", 1L, true);
        world.tagDesign("AMAZON", "   ", null);
        String blank = service().render("[amazon " + URL + "]", 1L, true);

        assertTrue(generated.startsWith("<style>\n.lb-amazon-card{"), generated);
        assertTrue(generated.contains("background:#fff"), generated);
        assertTrue(custom.startsWith("<style>\n.mine{color:red}\n</style>"), custom);
        assertEquals(generated, blank);
    }

    @Test
    void render_価格の取得時刻はUTCの壁時計でyyyy_MM_dd_HH_mm表記になる() {
        try (ContentWorld longLived = new ContentWorld(1_000_000L).actingAsAdmin(1L)) {
            longLived.cached(URL, ContentType.AMAZON, PRODUCT, LocalDateTime.of(2026, 9, 8, 20, 3, 35));

            String result = longLived.amazonTagRenderService.render("[amazon " + URL + "]", 1L, true);

            assertTrue(result.contains("2026/09/08 20:03時点の価格です"), result);
        }
    }

    @Test
    void render_タグが無ければそのまま返す() {
        String markdown = "普通の本文です。";
        assertEquals(markdown, service().render(markdown, 1L, true));
    }

    @Test
    void render_nullや空の本文はそのまま返す() {
        assertNull(service().render(null, 1L, true));
        assertEquals("", service().render("", 1L, true));
    }

    // ---- 実際のスクレイピング結果(Amazonの商品ページ)から描画する ----

    @Test
    void render_商品ページをスクレイピングして描画する() {
        world.page(URL, "<html><head><link rel=\"canonical\" href=\"https://amazon.co.jp/dp/canonical\"></head><body>"
                + "<span id=\"productTitle\"> 商品名 </span>"
                + "<span class=\"a-price\"><span class=\"a-offscreen\">1,500</span></span>"
                + "<img id=\"landingImage\" src=\"https://m.media-amazon.com/y.jpg\">"
                + "<div id=\"feature-bullets\"><ul><li><span class=\"a-list-item\">軽い</span></li></ul></div>"
                + "</body></html>");

        String result = service().render("[amazon " + URL + "]", 1L, true);

        com.fasterxml.jackson.databind.JsonNode data = EmbedMarkerTestSupport.firstMarker(result).get("data");
        assertEquals("商品名", data.get("productName").asText());
        assertEquals("￥1,500", data.get("price").asText());
        assertEquals("https://amazon.co.jp/dp/canonical", data.get("productUrl").asText());
        assertEquals("https://m.media-amazon.com/y.jpg", data.get("imageUrl").asText());
        assertEquals("軽い", data.get("summary").asText());
    }

    // ---- issue #1563: 取得したデータを目印として残す ----

    @Test
    void render_取得したデータを目印のJSONに生の値で残し_投稿時点のHTMLを目印で挟む() {
        try (ContentWorld longLived = new ContentWorld(1_000_000L).actingAsAdmin(1L)) {
            longLived.cached(URL, ContentType.AMAZON, PRODUCT, LocalDateTime.of(2026, 9, 8, 20, 3, 35));

            String result = longLived.amazonTagRenderService.render("[amazon " + URL + "]", 1L, true);

            com.fasterxml.jackson.databind.JsonNode marker = EmbedMarkerTestSupport.firstMarker(result);
            assertEquals("AMAZON", marker.get("type").asText());
            com.fasterxml.jackson.databind.JsonNode data = marker.get("data");
            assertEquals("商品名", data.get("productName").asText());
            assertEquals("￥1000", data.get("price").asText());
            assertEquals("概要", data.get("summary").asText());
            assertEquals(URL, data.get("productUrl").asText());
            assertEquals("https://m.media-amazon.com/x.jpg", data.get("imageUrl").asText());
            assertEquals("2026/09/08 20:03時点の価格です", data.get("priceTimestamp").asText());
            assertEquals(1, EmbedMarkerTestSupport.closeCount(result));
            assertTrue(result.indexOf("<!-- lbs:embed ") < result.indexOf("lb-amazon-card\""));
            assertTrue(result.indexOf("lb-amazon-card\"") < result.indexOf("<!-- /lbs:embed -->"));
        }
    }

    @Test
    void render_既定のカードは_a_の中にブロック要素を持たず改行も含まない_wpautopで分断されない() {
        try (ContentWorld longLived = new ContentWorld(1_000_000L).actingAsAdmin(1L)) {
            longLived.cached(URL, ContentType.AMAZON, PRODUCT, LocalDateTime.of(2026, 9, 8, 20, 3, 35));

            String result = longLived.amazonTagRenderService.render("[amazon " + URL + "]", 1L, true);

            String card = result.substring(result.indexOf("<a class=\"lb-amazon-card\""), result.indexOf("</a>") + 4);
            assertFalse(card.contains("<div"), card);
            assertFalse(card.contains("\n"), card);
            assertTrue(card.contains("<span class=\"lb-amazon-card-thumb\""), card);
            assertTrue(card.contains("<span class=\"lb-amazon-card-body\"><span class=\"lb-amazon-card-name\">商品名</span>"), card);
            assertTrue(card.contains("<span class=\"lb-amazon-card-summary\">概要</span>"), card);
            assertTrue(card.contains("<span class=\"lb-amazon-card-price\">￥1000</span>"), card);
            assertTrue(card.contains("<span class=\"lb-amazon-card-timestamp\">2026/09/08 20:03時点の価格です</span>"), card);
            assertTrue(card.endsWith("<span class=\"lb-amazon-card-cta\">Amazonで見る</span></span></a>"), card);
        }
    }

    @Test
    void render_概要も画像も無ければ既定のカードに出さない() {
        cached(Map.of("productName", "商品名", "productUrl", URL, "price", "1000"));

        String result = service().render("[amazon " + URL + "]", 1L, true);

        assertFalse(result.contains("<span class=\"lb-amazon-card-summary\""), result);
        assertFalse(result.contains("<span class=\"lb-amazon-card-thumb\""), result);
    }

    @Test
    void render_商品名が無ければURLを商品名にする() {
        cached(Map.of("productUrl", URL));

        String result = service().render("[amazon " + URL + "]", 1L, true);

        assertTrue(result.contains("<span class=\"lb-amazon-card-name\">" + URL + "</span>"), result);
    }

    @Test
    void render_カスタムテンプレートでも目印を付け_非本番サイトは商品URLを空にする() {
        world.tagDesign("AMAZON", null, "<p>{{productName}}|{{productUrl}}|{{price}}</p>");
        cached(PRODUCT);

        String production = service().render("[amazon " + URL + "]", 1L, true);
        String preview = service().render("[amazon " + URL + "]", 1L, false);

        assertTrue(production.contains("<p>商品名|" + URL + "|￥1000</p>"), production);
        assertTrue(preview.contains("<p>商品名||￥1000</p>"), preview);
    }

    @Test
    void render_非本番サイトでは目印の商品URLを空にする() {
        cached(PRODUCT);

        String result = service().render("[amazon " + URL + "]", 1L, false);

        assertEquals("", EmbedMarkerTestSupport.firstMarker(result).get("data").get("productUrl").asText());
        assertFalse(result.contains("href="));
    }

    @Test
    void render_単独の行のタグは空行で区切り_文中のタグは区切らない() {
        cached(PRODUCT);

        String alone = service().render("前\n[amazon " + URL + "]\n後", 1L, true);
        String inline = service().render("これ[amazon " + URL + "]です", 1L, true);

        assertTrue(alone.contains("-->\n\n<a class=\"lb-amazon-card\""), alone);
        assertTrue(alone.contains("</a>\n\n<!-- /lbs:embed -->\n後"), alone);
        assertTrue(inline.contains("これ<!-- lbs:embed "), inline);
        assertTrue(inline.contains("</a><!-- /lbs:embed -->です"), inline);
    }

    @Test
    void render_目印のJSONはコメントを閉じずタグ記法として解釈されない() {
        cached(Map.of("productName", "A --> <i>[amazon x]</i>", "productUrl", URL));

        String result = service().render("[amazon " + URL + "]", 1L, true);

        String raw = EmbedMarkerTestSupport.firstMarkerRaw(result);
        assertFalse(raw.contains("-") || raw.contains("<") || raw.contains(">") || raw.contains("[") || raw.contains("]"));
        assertEquals("A --> <i>[amazon x]</i>",
                EmbedMarkerTestSupport.firstMarker(result).get("data").get("productName").asText());
    }

    @Test
    void render_価格や画像が無ければ目印の価格関連は空になる() {
        cached(Map.of("productName", "商品名", "productUrl", "javascript:x"));

        String result = service().render("[amazon " + URL + "]", 1L, true);

        com.fasterxml.jackson.databind.JsonNode data = EmbedMarkerTestSupport.firstMarker(result).get("data");
        assertEquals("", data.get("price").asText());
        assertEquals("", data.get("priceTimestamp").asText());
        assertEquals(URL, data.get("productUrl").asText());
        assertEquals("", data.get("imageUrl").asText());
    }

    @Test
    void render_取得に失敗したリンクには目印を付けない() {
        String result = service().render("[amazon " + URL + "]", 1L, true);

        assertFalse(result.contains("lbs:embed"));
    }

    @Test
    void render_http_の商品URLと画像もリンク化する() {
        cached(Map.of("productName", "商品名", "productUrl", "http://amazon.co.jp/dp/plain",
                "imageUrl", "http://m.media-amazon.com/x.jpg"));

        String result = service().render("[amazon " + URL + "]", 1L, true);

        assertTrue(result.contains("href=\"http://amazon.co.jp/dp/plain\""), result);
        assertTrue(result.contains("url('http://m.media-amazon.com/x.jpg')"), result);
    }

    @Test
    void render_商品名が空文字ならURLを商品名にする() {
        cached(Map.of("productName", "", "productUrl", URL));

        String result = service().render("[amazon " + URL + "]", 1L, true);

        assertTrue(result.contains("<span class=\"lb-amazon-card-name\">" + URL + "</span>"), result);
    }
}
