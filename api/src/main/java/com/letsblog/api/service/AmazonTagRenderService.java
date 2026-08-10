package com.letsblog.api.service;

import com.letsblog.api.contentcache.ContentCacheService;
import com.letsblog.api.contentcache.ContentScrapingException;
import com.letsblog.api.domain.EmbedTagType;
import com.letsblog.api.dto.ContentCacheResponse;
import com.letsblog.api.dto.TagDesignColors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown本文中の組み込みタグ `[amazon URL]` を、Amazon商品情報を使った広告カードHTMLに展開する。
 * BlogCardTagRenderServiceと同様、flexmarkの拡張機構は使わずMarkdown文字列への
 * 正規表現前処理として実装する。
 * URL取得・スクレイピングに失敗した場合や無効なURLの場合は通常のリンク(またはプレーンテキスト)に
 * フォールバックし、記事全体のレンダリングは失敗させない。
 * スクレイピング結果(商品名・価格・URL等)は対象サイトが自由に設定できる非信頼な文字列のため、
 * HTML出力に含める際は必ずエスケープし、URLはhttp/https以外を許可しない(XSS対策)。
 * カードの配色はプロジェクトごとのデザイン設定(#150、TagDesignSettingService)に従う。
 */
@Service
@Slf4j
public class AmazonTagRenderService {

    private static final Pattern AMAZON_TAG_PATTERN =
            Pattern.compile("\\[amazon\\s+(\\S+)\\s*]", Pattern.CASE_INSENSITIVE);

    private static final String CARD_CLASS_ATTR = "class=\"lb-amazon-card\"";

    private final ContentCacheService contentCacheService;
    private final TagDesignSettingService tagDesignSettingService;

    public AmazonTagRenderService(
            ContentCacheService contentCacheService, TagDesignSettingService tagDesignSettingService) {
        this.contentCacheService = contentCacheService;
        this.tagDesignSettingService = tagDesignSettingService;
    }

    public String render(String markdown, Long projectId) {
        if (markdown == null || markdown.isEmpty()) {
            return markdown;
        }

        Matcher matcher = AMAZON_TAG_PATTERN.matcher(markdown);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String rawUrl = matcher.group(1);
            matcher.appendReplacement(result, Matcher.quoteReplacement(renderCard(rawUrl, projectId)));
        }
        matcher.appendTail(result);

        // 取得失敗で全てプレーンリンクにフォールバックした場合、使われないCSSを本文に混入させない
        if (result.indexOf(CARD_CLASS_ATTR) < 0) {
            return result.toString();
        }
        TagDesignColors colors = tagDesignSettingService.resolveColors(projectId, EmbedTagType.AMAZON);
        return "<style>\n" + buildStyle(colors) + "\n</style>\n\n" + result;
    }

    /**
     * CustomTagServiceの統合CSS生成からも呼ばれるためpackage-private。
     * customCssが設定されていれば、色ベースの生成CSSの代わりにそちらを丸ごと使う(完全上書き、issue #165)。
     * 未設定の場合のみ、背景色/テキスト色/アクセントカラーから組み立てる。
     */
    String buildStyle(TagDesignColors colors) {
        String customCss = colors.customCss();
        if (customCss != null && !customCss.isBlank()) {
            return customCss.trim();
        }
        return ".lb-amazon-card{display:flex;align-items:stretch;border:1px solid #e0e0e0;"
                + "border-radius:8px;overflow:hidden;text-decoration:none;color:" + colors.textColor()
                + ";max-width:100%;margin:1em 0;background:" + colors.backgroundColor()
                + ";transition:box-shadow .15s ease;}"
                + ".lb-amazon-card:hover{box-shadow:0 2px 8px rgba(0,0,0,.12);}"
                + ".lb-amazon-card-thumb{flex:0 0 120px;background-size:contain;background-repeat:no-repeat;"
                + "background-position:center;background-color:#fff;}"
                + ".lb-amazon-card-body{flex:1 1 auto;min-width:0;padding:12px 16px;display:flex;"
                + "flex-direction:column;gap:4px;}"
                + ".lb-amazon-card-name{font-weight:600;font-size:1em;overflow:hidden;display:-webkit-box;"
                + "-webkit-line-clamp:2;-webkit-box-orient:vertical;}"
                + ".lb-amazon-card-price{font-size:1.05em;font-weight:700;color:" + colors.accentColor() + ";}"
                + ".lb-amazon-card-cta{font-size:.8em;color:#fff;background:" + colors.accentColor()
                + ";border-radius:4px;padding:4px 10px;align-self:flex-start;margin-top:auto;}";
    }

    private String renderCard(String rawUrl, Long projectId) {
        try {
            ContentCacheResponse response = contentCacheService.resolve(rawUrl);
            Map<String, String> data = response.data();

            String href = sanitizeUrl(data.get("productUrl"), rawUrl);
            String imageUrl = sanitizeUrl(data.get("imageUrl"), null);
            String productName = HtmlUtils.htmlEscape(firstNonBlank(data.get("productName"), href));
            String price = HtmlUtils.htmlEscape(nullToEmpty(data.get("price")));
            String escapedHref = HtmlUtils.htmlEscape(href);
            String escapedImageUrl = imageUrl == null ? "" : HtmlUtils.htmlEscape(imageUrl);

            String customTemplate = tagDesignSettingService.resolveHtmlTemplate(projectId, EmbedTagType.AMAZON);
            if (customTemplate != null) {
                return EmbedTagTemplateRenderer.render(customTemplate, Map.of(
                        "productName", productName,
                        "price", price,
                        "productUrl", escapedHref,
                        "imageUrl", escapedImageUrl));
            }

            StringBuilder html = new StringBuilder();
            html.append("<a class=\"lb-amazon-card\" href=\"").append(escapedHref)
                    .append("\" target=\"_blank\" rel=\"noopener noreferrer nofollow sponsored\">");
            if (imageUrl != null) {
                html.append("<div class=\"lb-amazon-card-thumb\" style=\"background-image:url('")
                        .append(escapedImageUrl).append("')\"></div>");
            }
            html.append("<div class=\"lb-amazon-card-body\">")
                    .append("<div class=\"lb-amazon-card-name\">").append(productName).append("</div>");
            if (!price.isEmpty()) {
                html.append("<div class=\"lb-amazon-card-price\">").append(price).append("</div>");
            }
            html.append("<div class=\"lb-amazon-card-cta\">Amazonで見る</div>")
                    .append("</div></a>");
            return html.toString();
        } catch (IllegalArgumentException | ContentScrapingException e) {
            log.warn("[amazon]の展開に失敗したため通常のリンクにフォールバックします: url={}, error={}",
                    rawUrl, e.getMessage());
            return fallbackLink(rawUrl);
        }
    }

    private String fallbackLink(String rawUrl) {
        String escaped = HtmlUtils.htmlEscape(rawUrl);
        if (!isHttpUrl(rawUrl)) {
            // http/https以外(javascript:等)はリンク化せずプレーンテキストとして出力する
            return escaped;
        }
        return "<a href=\"" + escaped + "\" target=\"_blank\" rel=\"noopener noreferrer nofollow sponsored\">"
                + escaped + "</a>";
    }

    private String sanitizeUrl(String candidate, String fallback) {
        return isHttpUrl(candidate) ? candidate.trim() : fallback;
    }

    private boolean isHttpUrl(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return trimmed.regionMatches(true, 0, "http://", 0, 7)
                || trimmed.regionMatches(true, 0, "https://", 0, 8);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
