package com.letsblog.api.contentcache;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [amazon]組み込みタグ向けに、Amazon商品ページのHTMLから商品情報を抽出する。
 * Amazonはページ構造をレイアウト・地域・A/Bテストにより頻繁に変更するため、
 * 既知の複数セレクタを優先順位付きで試す(best-effort)。将来的にレイアウト変更で
 * 抽出できなくなった場合は空文字列を返し、呼び出し側でフォールバック表示させる。
 */
@Component
public class AmazonProductParser {

    private static final List<String> PRICE_SELECTORS = List.of(
            ".a-price .a-offscreen",
            "#priceblock_ourprice",
            "#priceblock_dealprice",
            "#priceblock_saleprice");

    private static final List<String> IMAGE_SELECTORS = List.of(
            "#landingImage",
            "#imgTagWrapperId img",
            "#imgBlkFront");

    public Map<String, String> parse(String html, String pageUrl) {
        Document doc = Jsoup.parse(html == null ? "" : html, pageUrl);

        Map<String, String> data = new LinkedHashMap<>();
        data.put("productName", textOf(doc.selectFirst("#productTitle")));
        data.put("imageUrl", imageUrl(doc));
        data.put("price", firstMatch(doc, PRICE_SELECTORS));
        data.put("productUrl", canonicalUrl(doc, pageUrl));
        return data;
    }

    private String imageUrl(Document doc) {
        for (String selector : IMAGE_SELECTORS) {
            Element img = doc.selectFirst(selector);
            if (img == null) {
                continue;
            }
            String src = img.attr("abs:data-old-hires");
            if (src.isBlank()) {
                src = img.attr("abs:src");
            }
            if (!src.isBlank()) {
                return src;
            }
        }
        return "";
    }

    private String firstMatch(Document doc, List<String> selectors) {
        for (String selector : selectors) {
            String text = textOf(doc.selectFirst(selector));
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private String canonicalUrl(Document doc, String fallbackUrl) {
        Element canonical = doc.selectFirst("link[rel=canonical]");
        String href = canonical == null ? "" : canonical.attr("abs:href");
        return href.isBlank() ? fallbackUrl : href;
    }

    private String textOf(Element element) {
        return element == null ? "" : element.text().trim();
    }
}
