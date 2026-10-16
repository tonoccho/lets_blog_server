package com.letsblog.content.contentcache;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** [amazon]向けの商品情報抽出。実物のパーサーへHTML文字列を渡して振る舞いを見る(外部との通信は無い)。 */
class AmazonProductParserTest {

    private static final String PAGE_URL = "https://www.amazon.co.jp/dp/B000";

    private final AmazonProductParser parser = new AmazonProductParser();

    @Test
    void 商品名_画像_価格_正規URL_箇条書きの概要を取り出す() {
        String html = "<html><head><link rel=\"canonical\" href=\"/dp/B000-canonical\"></head><body>"
                + "<span id=\"productTitle\">  商品名  </span>"
                + "<img id=\"landingImage\" data-old-hires=\"https://img.example/hires.jpg\" src=\"/small.jpg\">"
                + "<span class=\"a-price\"><span class=\"a-offscreen\">¥1,000</span></span>"
                + "<div id=\"feature-bullets\"><ul>"
                + "<li><span class=\"a-list-item\"> 特長1 </span></li>"
                + "<li><span class=\"a-list-item\">   </span></li>"
                + "<li><span class=\"a-list-item\">特長2</span></li>"
                + "</ul></div></body></html>";

        Map<String, String> data = parser.parse(html, PAGE_URL);

        assertEquals("商品名", data.get("productName"));
        assertEquals("https://img.example/hires.jpg", data.get("imageUrl"));
        assertEquals("¥1,000", data.get("price"));
        assertEquals("https://www.amazon.co.jp/dp/B000-canonical", data.get("productUrl"));
        assertEquals("特長1 / 特長2", data.get("summary"));
    }

    @Test
    void 高解像度画像が無ければsrcを使う() {
        String html = "<html><body><img id=\"landingImage\" src=\"/small.jpg\"></body></html>";

        assertEquals("https://www.amazon.co.jp/small.jpg", parser.parse(html, PAGE_URL).get("imageUrl"));
    }

    @Test
    void 先頭のセレクタに画像が無ければ次のセレクタを試す() {
        String html = "<html><body>"
                + "<img id=\"landingImage\">"
                + "<div id=\"imgTagWrapperId\"><img src=\"/wrapped.jpg\"></div></body></html>";

        assertEquals("https://www.amazon.co.jp/wrapped.jpg", parser.parse(html, PAGE_URL).get("imageUrl"));
    }

    @Test
    void どのセレクタにも画像が無ければ空() {
        assertEquals("", parser.parse("<html><body></body></html>", PAGE_URL).get("imageUrl"));
    }

    @Test
    void 価格は優先順位つきの複数セレクタから最初に見つかったものを使う() {
        String html = "<html><body><span id=\"priceblock_ourprice\">  </span>"
                + "<span id=\"priceblock_dealprice\">¥500</span>"
                + "<span id=\"priceblock_saleprice\">¥400</span></body></html>";

        assertEquals("¥500", parser.parse(html, PAGE_URL).get("price"));
    }

    @Test
    void 箇条書きが無ければmeta_descriptionを概要にし_正規URLが無ければページURLを使う() {
        String html = "<html><head><meta name=\"description\" content=\"  メタの説明  \"></head>"
                + "<body><div id=\"feature-bullets\"><ul><li><span class=\"a-list-item\"> </span></li></ul></div>"
                + "</body></html>";

        Map<String, String> data = parser.parse(html, PAGE_URL);

        assertEquals("メタの説明", data.get("summary"));
        assertEquals(PAGE_URL, data.get("productUrl"));
        assertEquals("", data.get("productName"));
        assertEquals("", data.get("price"));
    }

    @Test
    void 箇条書きもmeta_descriptionも無ければ概要は空でHTMLがnullでも落ちない() {
        Map<String, String> data = parser.parse(null, PAGE_URL);

        assertEquals("", data.get("summary"));
        assertEquals(PAGE_URL, data.get("productUrl"));
    }
}
