package com.letsblog.content.contentcache;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** [blogcard]向けのOGP抽出。実物のパーサーへHTML文字列を渡して振る舞いを見る(外部との通信は無い)。 */
class OgpMetadataParserTest {

    private final OgpMetadataParser parser = new OgpMetadataParser();

    @Test
    void OGPメタタグから各項目を取り出し_相対のog_imageは絶対URLにする() {
        String html = "<html><head>"
                + "<meta property=\"og:title\" content=\"  タイトル  \">"
                + "<meta property=\"og:description\" content=\"説明\">"
                + "<meta property=\"og:image\" content=\"/img/a.png\">"
                + "<meta property=\"og:site_name\" content=\"サイト\">"
                + "<meta property=\"og:url\" content=\"https://example.com/canonical\">"
                + "</head><body></body></html>";

        Map<String, String> data = parser.parse(html, "https://example.com/page");

        assertEquals("タイトル", data.get("title"));
        assertEquals("説明", data.get("description"));
        assertEquals("https://example.com/img/a.png", data.get("imageUrl"));
        assertEquals("サイト", data.get("siteName"));
        assertEquals("https://example.com/canonical", data.get("url"));
    }

    @Test
    void OGPが無ければtitle_meta_description_ホスト名_ページURLへフォールバックする() {
        String html = "<html><head><title>ページ題</title>"
                + "<meta name=\"description\" content=\"メタ説明\"></head><body></body></html>";

        Map<String, String> data = parser.parse(html, "https://blog.example.com/p/1");

        assertEquals("ページ題", data.get("title"));
        assertEquals("メタ説明", data.get("description"));
        assertEquals("", data.get("imageUrl"));
        assertEquals("blog.example.com", data.get("siteName"));
        assertEquals("https://blog.example.com/p/1", data.get("url"));
    }

    @Test
    void 空白だけのOGPの値は無いものとして扱う() {
        String html = "<html><head><title>題</title><meta property=\"og:title\" content=\"   \"></head></html>";

        assertEquals("題", parser.parse(html, "https://example.com/").get("title"));
    }

    @Test
    void HTMLがnullなら全項目が空でsiteNameとurlだけページURLから決まる() {
        Map<String, String> data = parser.parse(null, "https://example.com/x");

        assertEquals("", data.get("title"));
        assertEquals("", data.get("description"));
        assertEquals("", data.get("imageUrl"));
        assertEquals("example.com", data.get("siteName"));
        assertEquals("https://example.com/x", data.get("url"));
    }

    @Test
    void ページURLがURIとして不正ならsiteNameは空() {
        Map<String, String> data = parser.parse("<html></html>", "not a valid uri");

        assertEquals("", data.get("siteName"));
        assertEquals("not a valid uri", data.get("url"));
    }

    @Test
    void ページURLにホストが無ければsiteNameは空() {
        Map<String, String> data = parser.parse("<html></html>", "mailto:someone@example.com");

        assertEquals("", data.get("siteName"));
    }

    @Test
    void ページURLがnullなら例外() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> parser.parse("<html></html>", null));
    }
}
