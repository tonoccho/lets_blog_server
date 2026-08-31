package com.letsblog.api.contentcache;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OgpMetadataParserTest {

    private final OgpMetadataParser parser = new OgpMetadataParser();

    @Test
    void parse_OGPメタタグから各フィールドを抽出する() {
        String html = "<html><head>"
                + "<meta property=\"og:title\" content=\"サンプル記事\">"
                + "<meta property=\"og:description\" content=\"記事の説明\">"
                + "<meta property=\"og:image\" content=\"/images/eyecatch.png\">"
                + "<meta property=\"og:site_name\" content=\"サンプルブログ\">"
                + "<meta property=\"og:url\" content=\"https://example.com/canonical\">"
                + "</head><body></body></html>";

        Map<String, String> data = parser.parse(html, "https://example.com/posts/1");

        assertEquals("サンプル記事", data.get("title"));
        assertEquals("記事の説明", data.get("description"));
        assertEquals("https://example.com/images/eyecatch.png", data.get("imageUrl"));
        assertEquals("サンプルブログ", data.get("siteName"));
        assertEquals("https://example.com/canonical", data.get("url"));
    }

    @Test
    void parse_OGPメタタグがない場合はtitleタグとURLのホスト名にフォールバックする() {
        String html = "<html><head><title>ページタイトル</title></head><body></body></html>";

        Map<String, String> data = parser.parse(html, "https://example.com/posts/1");

        assertEquals("ページタイトル", data.get("title"));
        assertEquals("", data.get("description"));
        assertEquals("", data.get("imageUrl"));
        assertEquals("example.com", data.get("siteName"));
        assertEquals("https://example.com/posts/1", data.get("url"));
    }

    @Test
    void parse_meta_name_descriptionにもフォールバックする() {
        String html = "<html><head><meta name=\"description\" content=\"通常のdescription\"></head></html>";

        Map<String, String> data = parser.parse(html, "https://example.com/");

        assertEquals("通常のdescription", data.get("description"));
    }
}
