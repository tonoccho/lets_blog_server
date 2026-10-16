package com.letsblog.content.service;

import com.letsblog.content.domain.ContentType;
import com.letsblog.content.testsupport.ContentWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #641: BlogCardTagRenderServiceは非信頼なOGPスクレイピング結果(タイトル・説明等)を
 * HTMLへ埋め込む。AmazonTagRenderServiceと同様のXSS対策を検証する。
 *
 * <p>issue #1821: ContentCacheService・OGPパーサー・ProjectBridgeClient・CurrentActorServiceは実物。
 * スクレイピング先のページはPlaywrightのBrowser(外部プロセス)をモックして返し、キャッシュ行はリポジトリ(DB)に置く。
 * project-serviceはHTTPフェイク。
 */
class BlogCardTagRenderServiceTest {

    private static final String URL = "https://example.com/article";
    private static final String TAG_DESIGN_PATH = "/api/internal/project/tag-design/BLOGCARD";

    private ContentWorld world;

    @BeforeEach
    void setUp() {
        world = new ContentWorld().actingAsAdmin(1L);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private BlogCardTagRenderService service() {
        return world.blogCardTagRenderService;
    }

    /** ページをスクレイピングして得たOGPの内容。 */
    private void scraped(String title, String description, String siteName, String url, String image) {
        world.page(URL, ContentWorld.ogHtml(title, description, siteName, url, image));
    }

    /** キャッシュ済みの内容(任意の値を持てる)。 */
    private void cached(Map<String, String> data) {
        world.cached(URL, ContentType.BLOGCARD, data, LocalDateTime.now());
    }

    @Test
    void render_タイトルにHTMLタグを含むレスポンスはエスケープされて出力される() {
        scraped("<img src=x onerror=alert(1)>", "説明文", null, URL, null);

        String result = service().render("[blogcard " + URL + "]", 1L);

        assertFalse(result.contains("<img src=x onerror=alert(1)>"));
        assertTrue(result.contains("&lt;img"));
    }

    @Test
    void render_javascriptプロトコルのURLはリンク化されない() {
        scraped("タイトル", "説明文", null, "javascript:alert(1)", null);

        String result = service().render("[blogcard " + URL + "]", 1L);

        assertFalse(result.contains("href=\"javascript:"));
        assertTrue(result.contains("href=\"" + URL + "\""));
    }

    @Test
    void render_スクレイピング失敗時は通常のリンクにフォールバックする() {
        // ブラウザがページを開けない(登録の無いURL)
        String result = service().render("[blogcard " + URL + "]", 1L);

        assertTrue(result.contains("<a href=\"" + URL + "\""));
        assertFalse(result.contains("lb-blogcard"));
    }

    @Test
    void render_内部アドレスに解決されるURLは取得せず通常のリンクにフォールバックする() {
        String internal = "https://internal.example.com/secret";

        String result = service().render("[blogcard " + internal + "]", 1L);

        assertTrue(result.contains("<a href=\"" + internal + "\""));
        assertTrue(world.navigatedUrls.isEmpty());
    }

    @Test
    void render_非httpスキームかつ取得失敗時はプレーンテキストにフォールバックする() {
        String result = service().render("[blogcard javascript:alert(1)]", 1L);

        assertFalse(result.contains("<a "));
        assertTrue(result.contains("javascript:alert(1)"));
    }

    @Test
    void render_タグが無ければそのまま返す() {
        String markdown = "普通の本文です。";
        assertEquals(markdown, service().render(markdown, 1L));
        assertTrue(world.http.requests().isEmpty());
    }

    @Test
    void render_nullや空の本文はそのまま返す() {
        assertNull(service().render(null, 1L));
        assertEquals("", service().render("", 1L));
    }

    /**
     * issue #760: プロジェクトに紐付いていないサイトへの公開ではprojectId=nullで呼ばれる。
     * projectIdはそのままタグデザイン解決へ渡し(受け側が固定のデフォルト値を返す暫定対応、issue #763)、
     * レンダリング自体は成功する。
     */
    @Test
    void render_projectIdがnullでもタグデザインを解決してカードを描画する() {
        scraped("タイトル", "説明文", null, URL, null);

        String result = service().render("[blogcard " + URL + "]", null);

        assertTrue(result.contains("lb-blogcard"));
        assertEquals("projectId=", world.http.requests("GET", TAG_DESIGN_PATH).get(0).query());
    }

    @Test
    void render_projectId指定ありはそのIDでタグデザインを解決しスタイルを先頭に付ける() {
        scraped("タイトル", "説明文", null, URL, null);

        String result = service().render("[blogcard " + URL + "]", 4L);

        assertTrue(result.startsWith("<style>\n.lb-blogcard{"), result);
        assertTrue(result.contains("border-left:4px solid #f00"), result);
        assertEquals("projectId=4", world.http.requests("GET", TAG_DESIGN_PATH).get(0).query());
    }

    @Test
    void render_デザインにcustomCssがあればそれをスタイルに使う() {
        world.tagDesign("BLOGCARD", "  .mine{color:red}  ", null);
        scraped("タイトル", "説明文", null, URL, null);

        String result = service().render("[blogcard " + URL + "]", 1L);

        assertTrue(result.startsWith("<style>\n.mine{color:red}\n</style>"), result);
    }

    @Test
    void render_デザインのcustomCssが空白なら色から組み立てる() {
        world.tagDesign("BLOGCARD", "   ", null);
        scraped("タイトル", "説明文", null, URL, null);

        String result = service().render("[blogcard " + URL + "]", 1L);

        assertTrue(result.startsWith("<style>\n.lb-blogcard{"), result);
    }

    private static final String SITE = "サイト名";
    private static final String IMAGE = "https://example.com/og.png";

    private void fullPage() {
        scraped("記事のタイトル", "記事の説明", SITE, URL, IMAGE);
    }

    // ---- issue #1563: 取得したデータを目印として残す ----

    @Test
    void render_取得したデータを目印のJSONに生の値で残し_投稿時点のHTMLを目印で挟む() {
        fullPage();

        String result = service().render("[blogcard " + URL + "]", 1L);

        com.fasterxml.jackson.databind.JsonNode marker = EmbedMarkerTestSupport.firstMarker(result);
        assertEquals("BLOGCARD", marker.get("type").asText());
        com.fasterxml.jackson.databind.JsonNode data = marker.get("data");
        assertEquals("記事のタイトル", data.get("title").asText());
        assertEquals("記事の説明", data.get("description").asText());
        assertEquals("サイト名", data.get("siteName").asText());
        assertEquals(URL, data.get("url").asText());
        assertEquals(IMAGE, data.get("imageUrl").asText());
        assertEquals(1, EmbedMarkerTestSupport.closeCount(result));
        assertTrue(result.contains("<a class=\"lb-blogcard\" href=\"" + URL + "\""));
        assertTrue(result.indexOf("<!-- lbs:embed ") < result.indexOf("lb-blogcard\""));
        assertTrue(result.indexOf("lb-blogcard\"") < result.indexOf("<!-- /lbs:embed -->"));
    }

    @Test
    void render_既定のカードは_a_の中にブロック要素を持たず改行も含まない_wpautopで分断されない() {
        fullPage();

        String result = service().render("[blogcard " + URL + "]", 1L);

        String card = result.substring(result.indexOf("<a class=\"lb-blogcard\""), result.indexOf("</a>") + 4);
        assertFalse(card.contains("<div"), card);
        assertFalse(card.contains("\n"), card);
        assertTrue(card.contains("<span class=\"lb-blogcard-thumb\""), card);
        assertTrue(card.contains("<span class=\"lb-blogcard-body\"><span class=\"lb-blogcard-title\">記事のタイトル</span>"), card);
        assertTrue(card.contains("<span class=\"lb-blogcard-description\">記事の説明</span>"), card);
        assertTrue(card.contains("<span class=\"lb-blogcard-site\">サイト名</span></span></a>"), card);
    }

    @Test
    void render_画像がなければ既定のカードに_thumb_を出さない() {
        scraped("T", null, null, null, null);

        String result = service().render("[blogcard " + URL + "]", 1L);

        assertFalse(result.contains("<span class=\"lb-blogcard-thumb\""), result);
        assertFalse(result.contains("<div class=\"lb-blogcard-"), result);
    }

    @Test
    void render_タイトルが無ければURLをタイトルにする() {
        scraped(null, null, null, null, null);

        String result = service().render("[blogcard " + URL + "]", 1L);

        assertTrue(result.contains("<span class=\"lb-blogcard-title\">" + URL + "</span>"), result);
        assertEquals(URL, EmbedMarkerTestSupport.firstMarker(result).get("data").get("title").asText());
    }

    @Test
    void render_単独の行のタグは目印とカードを別のHTMLブロックにするため空行で区切る() {
        fullPage();

        String result = service().render("前の段落\n[blogcard " + URL + "]\n次の段落", 1L);

        assertTrue(result.contains("-->\n\n<a class=\"lb-blogcard\""), result);
        assertTrue(result.contains("</a>\n\n<!-- /lbs:embed -->\n次の段落"), result);
    }

    @Test
    void render_文中のタグは段落を割らないよう区切りなしで目印を付ける() {
        fullPage();

        String result = service().render("見て[blogcard " + URL + "]ね", 1L);

        assertTrue(result.contains("見て<!-- lbs:embed "), result);
        assertTrue(result.contains("--><a class=\"lb-blogcard\""), result);
        assertTrue(result.contains("</a><!-- /lbs:embed -->ね"), result);
    }

    @Test
    void render_目印のJSONはコメントを閉じず_HTMLやタグ記法として解釈されない() {
        scraped("A --> <b>[toc]</b> [blogcard x]", null, null, URL, null);

        String result = service().render("[blogcard " + URL + "]", 1L);

        String raw = EmbedMarkerTestSupport.firstMarkerRaw(result);
        assertFalse(raw.contains("-"));
        assertFalse(raw.contains("<"));
        assertFalse(raw.contains(">"));
        assertFalse(raw.contains("["));
        assertFalse(raw.contains("]"));
        assertEquals("A --> <b>[toc]</b> [blogcard x]",
                EmbedMarkerTestSupport.firstMarker(result).get("data").get("title").asText());
    }

    @Test
    void render_カスタムテンプレートでもデータを目印に残す() {
        world.tagDesign("BLOGCARD", null, "<section>{{title}}</section>");
        fullPage();

        String result = service().render("[blogcard " + URL + "]", 1L);

        assertTrue(result.contains("<section>記事のタイトル</section>"));
        assertEquals("記事のタイトル", EmbedMarkerTestSupport.firstMarker(result).get("data").get("title").asText());
    }

    @Test
    void render_http以外のURLは目印のデータに入れず_元のURLに置き換える() {
        // キャッシュ行には、取得時の内容がそのまま入っている(http以外のURLを含みうる)
        cached(Map.of("title", "t", "url", "javascript:alert(1)", "imageUrl", "javascript:alert(2)"));

        String result = service().render("[blogcard " + URL + "]", 1L);

        assertFalse(result.contains("javascript:"));
        com.fasterxml.jackson.databind.JsonNode data = EmbedMarkerTestSupport.firstMarker(result).get("data");
        assertEquals(URL, data.get("url").asText());
        assertEquals("", data.get("imageUrl").asText());
    }

    @Test
    void render_キャッシュ済みの内容に項目が無くてもカードを描画する() {
        cached(Map.of("title", "t"));

        String result = service().render("[blogcard " + URL + "]", 1L);

        assertTrue(result.contains("<a class=\"lb-blogcard\" href=\"" + URL + "\""), result);
        assertEquals("", EmbedMarkerTestSupport.firstMarker(result).get("data").get("siteName").asText());
        assertTrue(world.navigatedUrls.isEmpty(), "TTL内のキャッシュはブラウザを起こさない");
    }

    @Test
    void render_取得に失敗したリンクには目印を付けない() {
        String result = service().render("[blogcard " + URL + "]", 1L);

        assertFalse(result.contains("lbs:embed"));
    }

    @Test
    void render_http_のURLもリンク化する() {
        scraped("タイトル", "説明", null, "http://example.com/plain", "http://example.com/og.png");

        String result = service().render("[blogcard " + URL + "]", 1L);

        assertTrue(result.contains("href=\"http://example.com/plain\""), result);
        assertTrue(result.contains("url('http://example.com/og.png')"), result);
    }

    @Test
    void render_キャッシュ済みの内容にタイトルの項目が無ければURLをタイトルにする() {
        cached(Map.of("url", URL));

        String result = service().render("[blogcard " + URL + "]", 1L);

        assertTrue(result.contains("<span class=\"lb-blogcard-title\">" + URL + "</span>"), result);
    }
}
