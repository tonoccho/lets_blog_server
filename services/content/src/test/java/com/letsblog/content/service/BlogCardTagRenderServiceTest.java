package com.letsblog.content.service;

import com.letsblog.content.client.ProjectBridgeClient;
import com.letsblog.content.contentcache.ContentCacheService;
import com.letsblog.content.contentcache.ContentScrapingException;
import com.letsblog.content.domain.ContentType;
import com.letsblog.content.dto.ContentCacheResponse;
import com.letsblog.content.dto.TagDesignColors;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * issue #641: BlogCardTagRenderServiceは非信頼なOGPスクレイピング結果(タイトル・説明等)を
 * HTMLへ埋め込む。AmazonTagRenderServiceと同様のXSS対策を検証する。
 */
@ExtendWith(MockitoExtension.class)
class BlogCardTagRenderServiceTest {

    @Mock
    private ContentCacheService contentCacheService;

    @Mock
    private ProjectBridgeClient projectBridgeClient;

    @Mock
    private CurrentActorService currentActorService;

    private BlogCardTagRenderService service() {
        return new BlogCardTagRenderService(contentCacheService, projectBridgeClient, currentActorService);
    }

    private void stubTagDesign() {
        ProjectBridgeClient.TagDesignResponse response =
                new ProjectBridgeClient.TagDesignResponse("#fff", "#000", "#f00", null, null);
        lenient().when(projectBridgeClient.resolveTagDesign(any(), anyString(), any())).thenReturn(response);
        lenient().when(projectBridgeClient.toColors(any()))
                .thenReturn(new TagDesignColors("#fff", "#000", "#f00", null));
    }

    @Test
    void render_タイトルにHTMLタグを含むレスポンスはエスケープされて出力される() {
        stubTagDesign();
        Map<String, String> data = Map.of(
                "title", "<img src=x onerror=alert(1)>",
                "url", "https://example.com/article",
                "description", "説明文");
        when(contentCacheService.resolve("https://example.com/article"))
                .thenReturn(new ContentCacheResponse(
                        "https://example.com/article", ContentType.BLOGCARD, data,
                        Instant.now(), Instant.now()));

        String result = service().render("[blogcard https://example.com/article]", 1L);

        assertFalse(result.contains("<img src=x onerror=alert(1)>"));
        assertTrue(result.contains("&lt;img"));
    }

    @Test
    void render_javascriptプロトコルのURLはリンク化されない() {
        stubTagDesign();
        Map<String, String> data = Map.of(
                "title", "タイトル",
                "url", "javascript:alert(1)",
                "description", "説明文");
        when(contentCacheService.resolve("https://example.com/article"))
                .thenReturn(new ContentCacheResponse(
                        "https://example.com/article", ContentType.BLOGCARD, data,
                        Instant.now(), Instant.now()));

        String result = service().render("[blogcard https://example.com/article]", 1L);

        assertFalse(result.contains("href=\"javascript:"));
    }

    @Test
    void render_スクレイピング失敗時は通常のリンクにフォールバックする() {
        when(contentCacheService.resolve(anyString()))
                .thenThrow(new ContentScrapingException("取得失敗", null));

        String result = service().render("[blogcard https://example.com/article]", 1L);

        assertTrue(result.contains("<a href=\"https://example.com/article\""));
        assertFalse(result.contains("lb-blogcard"));
    }

    @Test
    void render_非httpスキームかつ取得失敗時はプレーンテキストにフォールバックする() {
        when(contentCacheService.resolve(anyString()))
                .thenThrow(new ContentScrapingException("不正なURL", null));

        String result = service().render("[blogcard javascript:alert(1)]", 1L);

        assertFalse(result.contains("<a "));
        assertTrue(result.contains("javascript:alert(1)"));
    }

    @Test
    void render_タグが無ければそのまま返す() {
        String markdown = "普通の本文です。";
        assertEquals(markdown, service().render(markdown, 1L));
    }

    /**
     * issue #760: プロジェクトに紐付いていないサイトへの公開ではprojectId=nullで呼ばれる。
     * projectIdはそのままタグデザイン解決へ渡し(受け側が固定のデフォルト値を返す暫定対応、issue #763)、
     * レンダリング自体は成功する。
     */
    @Test
    void render_projectIdがnullでもタグデザインを解決してカードを描画する() {
        stubTagDesign();
        Map<String, String> data = Map.of(
                "title", "タイトル",
                "url", "https://example.com/article",
                "description", "説明文");
        when(contentCacheService.resolve("https://example.com/article"))
                .thenReturn(new ContentCacheResponse(
                        "https://example.com/article", ContentType.BLOGCARD, data,
                        Instant.now(), Instant.now()));

        String result = service().render("[blogcard https://example.com/article]", null);

        assertTrue(result.contains("lb-blogcard"));
        org.mockito.Mockito.verify(projectBridgeClient, org.mockito.Mockito.atLeastOnce())
                .resolveTagDesign(org.mockito.ArgumentMatchers.isNull(), anyString(), any());
    }

    private void stubCard(String url, Map<String, String> data) {
        when(contentCacheService.resolve(url))
                .thenReturn(new ContentCacheResponse(url, ContentType.BLOGCARD, data, Instant.now(), Instant.now()));
    }

    private static final Map<String, String> FULL_DATA = Map.of(
            "title", "記事のタイトル",
            "description", "記事の説明",
            "siteName", "サイト名",
            "url", "https://example.com/article",
            "imageUrl", "https://example.com/og.png");

    // ---- issue #1563: 取得したデータを目印として残す ----

    @Test
    void render_取得したデータを目印のJSONに生の値で残し_投稿時点のHTMLを目印で挟む() {
        stubTagDesign();
        stubCard("https://example.com/article", FULL_DATA);

        String result = service().render("[blogcard https://example.com/article]", 1L);

        com.fasterxml.jackson.databind.JsonNode marker = EmbedMarkerTestSupport.firstMarker(result);
        assertEquals("BLOGCARD", marker.get("type").asText());
        com.fasterxml.jackson.databind.JsonNode data = marker.get("data");
        assertEquals("記事のタイトル", data.get("title").asText());
        assertEquals("記事の説明", data.get("description").asText());
        assertEquals("サイト名", data.get("siteName").asText());
        assertEquals("https://example.com/article", data.get("url").asText());
        assertEquals("https://example.com/og.png", data.get("imageUrl").asText());
        assertEquals(1, EmbedMarkerTestSupport.closeCount(result));
        assertTrue(result.contains("<a class=\"lb-blogcard\" href=\"https://example.com/article\""));
        assertTrue(result.indexOf("<!-- lbs:embed ") < result.indexOf("lb-blogcard\""));
        assertTrue(result.indexOf("lb-blogcard\"") < result.indexOf("<!-- /lbs:embed -->"));
    }

    @Test
    void render_既定のカードは_a_の中にブロック要素を持たず改行も含まない_wpautopで分断されない() {
        stubTagDesign();
        stubCard("https://example.com/article", FULL_DATA);

        String result = service().render("[blogcard https://example.com/article]", 1L);

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
        stubTagDesign();
        stubCard("https://example.com/article", Map.of("title", "T"));

        String result = service().render("[blogcard https://example.com/article]", 1L);

        assertFalse(result.contains("<span class=\"lb-blogcard-thumb\""), result);
        assertFalse(result.contains("<div class=\"lb-blogcard-"), result);
    }

    @Test
    void render_単独の行のタグは目印とカードを別のHTMLブロックにするため空行で区切る() {
        stubTagDesign();
        stubCard("https://example.com/article", FULL_DATA);

        String result = service().render("前の段落\n[blogcard https://example.com/article]\n次の段落", 1L);

        assertTrue(result.contains("-->\n\n<a class=\"lb-blogcard\""), result);
        assertTrue(result.contains("</a>\n\n<!-- /lbs:embed -->\n次の段落"), result);
    }

    @Test
    void render_文中のタグは段落を割らないよう区切りなしで目印を付ける() {
        stubTagDesign();
        stubCard("https://example.com/article", FULL_DATA);

        String result = service().render("見て[blogcard https://example.com/article]ね", 1L);

        assertTrue(result.contains("見て<!-- lbs:embed "), result);
        assertTrue(result.contains("--><a class=\"lb-blogcard\""), result);
        assertTrue(result.contains("</a><!-- /lbs:embed -->ね"), result);
    }

    @Test
    void render_目印のJSONはコメントを閉じず_HTMLやタグ記法として解釈されない() {
        stubTagDesign();
        stubCard("https://example.com/article", Map.of(
                "title", "A --> <b>[toc]</b> [blogcard x]",
                "url", "https://example.com/article"));

        String result = service().render("[blogcard https://example.com/article]", 1L);

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
        lenient().when(projectBridgeClient.resolveTagDesign(any(), anyString(), any())).thenReturn(
                new ProjectBridgeClient.TagDesignResponse("#fff", "#000", "#f00", null, "<section>{{title}}</section>"));
        lenient().when(projectBridgeClient.toColors(any()))
                .thenReturn(new TagDesignColors("#fff", "#000", "#f00", null));
        stubCard("https://example.com/article", FULL_DATA);

        String result = service().render("[blogcard https://example.com/article]", 1L);

        assertTrue(result.contains("<section>記事のタイトル</section>"));
        assertEquals("記事のタイトル", EmbedMarkerTestSupport.firstMarker(result).get("data").get("title").asText());
    }

    @Test
    void render_http以外のURLは目印のデータに入れず_元のURLに置き換える() {
        stubTagDesign();
        stubCard("https://example.com/article", Map.of(
                "title", "t", "url", "javascript:alert(1)", "imageUrl", "javascript:alert(2)"));

        String result = service().render("[blogcard https://example.com/article]", 1L);

        assertFalse(result.contains("javascript:"));
        com.fasterxml.jackson.databind.JsonNode data = EmbedMarkerTestSupport.firstMarker(result).get("data");
        assertEquals("https://example.com/article", data.get("url").asText());
        assertEquals("", data.get("imageUrl").asText());
    }

    @Test
    void render_取得に失敗したリンクには目印を付けない() {
        when(contentCacheService.resolve(anyString())).thenThrow(new ContentScrapingException("取得失敗", null));

        String result = service().render("[blogcard https://example.com/article]", 1L);

        assertFalse(result.contains("lbs:embed"));
    }
}
