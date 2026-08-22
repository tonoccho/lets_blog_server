package com.letsblog.api.contentcache;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** [blogcard]組み込みタグ向けに、HTML中のOGPメタタグ(og:title等)からブログカード用の情報を抽出する。 */
@Component
public class OgpMetadataParser {

    public Map<String, String> parse(String html, String pageUrl) {
        Document doc = Jsoup.parse(html == null ? "" : html, pageUrl);

        Map<String, String> data = new LinkedHashMap<>();
        data.put("title", firstNonBlank(ogContent(doc, "og:title"), doc.title()));
        data.put("description", firstNonBlank(ogContent(doc, "og:description"), metaContent(doc, "description")));
        data.put("imageUrl", ogImageUrl(doc));
        data.put("siteName", firstNonBlank(ogContent(doc, "og:site_name"), hostOf(pageUrl)));
        data.put("url", firstNonBlank(ogContent(doc, "og:url"), pageUrl));
        return data;
    }

    private String ogContent(Document doc, String property) {
        Element tag = doc.selectFirst("meta[property=" + property + "]");
        return tag == null ? "" : tag.attr("content");
    }

    /** og:imageは相対URLで記述される場合があるため、ページのbase URIを基準に絶対URLへ解決する。 */
    private String ogImageUrl(Document doc) {
        Element tag = doc.selectFirst("meta[property=og:image]");
        return tag == null ? "" : tag.attr("abs:content");
    }

    private String metaContent(Document doc, String name) {
        Element tag = doc.selectFirst("meta[name=" + name + "]");
        return tag == null ? "" : tag.attr("content");
    }

    private String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
