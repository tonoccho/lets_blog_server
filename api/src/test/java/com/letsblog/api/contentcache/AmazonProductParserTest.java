package com.letsblog.api.contentcache;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AmazonProductParserTest {

    private final AmazonProductParser parser = new AmazonProductParser();

    @Test
    void parse_商品ページのHTMLから商品情報を抽出する() {
        String html = "<html><head>"
                + "<link rel=\"canonical\" href=\"https://www.amazon.co.jp/dp/B000000000\">"
                + "</head><body>"
                + "<span id=\"productTitle\">  サンプル商品名  </span>"
                + "<span class=\"a-price\"><span class=\"a-offscreen\">￥1,980</span></span>"
                + "<img id=\"landingImage\" src=\"https://m.media-amazon.com/images/small.jpg\" "
                + "data-old-hires=\"https://m.media-amazon.com/images/large.jpg\">"
                + "</body></html>";

        Map<String, String> data = parser.parse(html, "https://www.amazon.co.jp/dp/B000000000?tag=affiliate-22");

        assertEquals("サンプル商品名", data.get("productName"));
        assertEquals("￥1,980", data.get("price"));
        assertEquals("https://m.media-amazon.com/images/large.jpg", data.get("imageUrl"));
        assertEquals("https://www.amazon.co.jp/dp/B000000000", data.get("productUrl"));
    }

    @Test
    void parse_data_old_hiresがなければsrc属性にフォールバックする() {
        String html = "<html><body>"
                + "<span id=\"productTitle\">商品</span>"
                + "<img id=\"landingImage\" src=\"https://m.media-amazon.com/images/only.jpg\">"
                + "</body></html>";

        Map<String, String> data = parser.parse(html, "https://www.amazon.co.jp/dp/B000000000");

        assertEquals("https://m.media-amazon.com/images/only.jpg", data.get("imageUrl"));
    }

    @Test
    void parse_canonicalリンクがなければ入力URLをproductUrlとして使う() {
        String html = "<html><body><span id=\"productTitle\">商品</span></body></html>";

        Map<String, String> data = parser.parse(html, "https://www.amazon.co.jp/dp/B000000000");

        assertEquals("https://www.amazon.co.jp/dp/B000000000", data.get("productUrl"));
    }

    @Test
    void parse_想定外のレイアウトでは空文字列を返しフォールバック可能にする() {
        String html = "<html><body><p>レイアウトが変わったページ</p></body></html>";

        Map<String, String> data = parser.parse(html, "https://www.amazon.co.jp/dp/B000000000");

        assertEquals("", data.get("productName"));
        assertEquals("", data.get("price"));
        assertEquals("", data.get("imageUrl"));
    }
}
