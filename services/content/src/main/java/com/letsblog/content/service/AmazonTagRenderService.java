package com.letsblog.content.service;

import com.letsblog.content.client.ProjectBridgeClient;
import com.letsblog.content.contentcache.ContentCacheService;
import com.letsblog.content.contentcache.ContentScrapingException;
import com.letsblog.content.dto.ContentCacheResponse;
import com.letsblog.content.dto.TagDesignColors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown本文中の組み込みタグ `[amazon URL]` を、Amazon商品情報を使った広告カードHTMLに展開する。
 * legacy-apiのAmazonTagRenderServiceと同じ実装(issue #576でcontent-serviceへ移管)。
 * BlogCardTagRenderServiceと同様、flexmarkの拡張機構は使わずMarkdown文字列への
 * 正規表現前処理として実装する。
 * URL取得・スクレイピングに失敗した場合や無効なURLの場合は通常のリンク(またはプレーンテキスト)に
 * フォールバックし、記事全体のレンダリングは失敗させない。
 * スクレイピング結果(商品名・価格・URL等)は対象サイトが自由に設定できる非信頼な文字列のため、
 * HTML出力に含める際は必ずエスケープし、URLはhttp/https以外を許可しない(XSS対策)。
 * カードの配色はプロジェクトごとのデザイン設定(#150、tag_design_settings)に従う。
 * 同テーブルはlegacy-apiに残るドメインのため、{@link ProjectBridgeClient}経由の内部ブリッジで解決する。
 */
@Service
@Slf4j
public class AmazonTagRenderService {

    private static final Pattern AMAZON_TAG_PATTERN =
            Pattern.compile("\\[amazon\\s+(\\S+)\\s*]", Pattern.CASE_INSENSITIVE);

    private static final String CARD_CLASS_ATTR = "class=\"lb-amazon-card\"";
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

    private final ContentCacheService contentCacheService;
    private final ProjectBridgeClient projectBridgeClient;
    private final CurrentActorService currentActorService;

    public AmazonTagRenderService(
            ContentCacheService contentCacheService, ProjectBridgeClient projectBridgeClient,
            CurrentActorService currentActorService) {
        this.contentCacheService = contentCacheService;
        this.projectBridgeClient = projectBridgeClient;
        this.currentActorService = currentActorService;
    }

    /**
     * isProductionSiteがfalseの場合(プロジェクトの本番サイト以外での表示/プレビュー)、商品リンクを
     * 非活性化してレンダリングする(issue #389)。呼び出し側はlegacy-apiのPostPublishService.isProductionSiteと
     * 同じ規約(Project.productionSiteIdとの比較)で判定すること。
     */
    public String render(String markdown, Long projectId, boolean isProductionSite) {
        if (markdown == null || markdown.isEmpty()) {
            return markdown;
        }

        Matcher matcher = AMAZON_TAG_PATTERN.matcher(markdown);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String rawUrl = matcher.group(1);
            matcher.appendReplacement(
                    result, Matcher.quoteReplacement(renderCard(rawUrl, projectId, isProductionSite)));
        }
        matcher.appendTail(result);

        // 取得失敗で全てプレーンリンクにフォールバックした場合、使われないCSSを本文に混入させない
        if (result.indexOf(CARD_CLASS_ATTR) < 0) {
            return result.toString();
        }
        TagDesignColors colors = projectBridgeClient.toColors(projectBridgeClient.resolveTagDesign(
                projectId, "AMAZON", currentActorService.getAuthorizationHeader()));
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
                + ".lb-amazon-card-summary{font-size:.85em;opacity:.75;overflow:hidden;display:-webkit-box;"
                + "-webkit-line-clamp:2;-webkit-box-orient:vertical;}"
                + ".lb-amazon-card-price{font-size:1.05em;font-weight:700;color:" + colors.accentColor() + ";}"
                + ".lb-amazon-card-timestamp{font-size:.75em;opacity:.6;}"
                + ".lb-amazon-card-cta{font-size:.8em;color:#fff;background:" + colors.accentColor()
                + ";border-radius:4px;padding:4px 10px;align-self:flex-start;margin-top:auto;}";
    }

    private String renderCard(String rawUrl, Long projectId, boolean isProductionSite) {
        try {
            ContentCacheResponse response = contentCacheService.resolve(rawUrl);
            Map<String, String> data = response.data();

            String href = sanitizeUrl(data.get("productUrl"), rawUrl);
            String imageUrl = sanitizeUrl(data.get("imageUrl"), null);
            String productName = HtmlUtils.htmlEscape(firstNonBlank(data.get("productName"), href));
            String price = HtmlUtils.htmlEscape(toYenPrice(data.get("price")));
            String summary = HtmlUtils.htmlEscape(nullToEmpty(data.get("summary")));
            String escapedHref = HtmlUtils.htmlEscape(href);
            String escapedImageUrl = imageUrl == null ? "" : HtmlUtils.htmlEscape(imageUrl);
            String fetchedAt = response.lastCheckedAt() == null
                    ? "" : response.lastCheckedAt().format(TIMESTAMP_FORMATTER);
            String priceTimestamp = fetchedAt.isEmpty() || price.isEmpty()
                    ? "" : fetchedAt + "時点の価格です";

            String customTemplate = projectBridgeClient.resolveTagDesign(
                    projectId, "AMAZON", currentActorService.getAuthorizationHeader()).htmlTemplate();
            if (customTemplate != null) {
                return EmbedTagTemplateRenderer.render(customTemplate, Map.of(
                        "productName", productName,
                        "price", price,
                        "productUrl", isProductionSite ? escapedHref : "",
                        "imageUrl", escapedImageUrl,
                        "summary", summary,
                        "priceTimestamp", priceTimestamp));
            }

            StringBuilder html = new StringBuilder();
            String tag = isProductionSite ? "a" : "div";
            html.append("<").append(tag).append(" class=\"lb-amazon-card\"");
            if (isProductionSite) {
                html.append(" href=\"").append(escapedHref)
                        .append("\" target=\"_blank\" rel=\"noopener noreferrer nofollow sponsored\"");
            }
            html.append(">");
            if (imageUrl != null) {
                html.append("<div class=\"lb-amazon-card-thumb\" style=\"background-image:url('")
                        .append(escapedImageUrl).append("')\"></div>");
            }
            html.append("<div class=\"lb-amazon-card-body\">")
                    .append("<div class=\"lb-amazon-card-name\">").append(productName).append("</div>");
            if (!summary.isEmpty()) {
                html.append("<div class=\"lb-amazon-card-summary\">").append(summary).append("</div>");
            }
            if (!price.isEmpty()) {
                html.append("<div class=\"lb-amazon-card-price\">").append(price).append("</div>");
            }
            if (!priceTimestamp.isEmpty()) {
                html.append("<div class=\"lb-amazon-card-timestamp\">").append(priceTimestamp).append("</div>");
            }
            html.append("<div class=\"lb-amazon-card-cta\">Amazonで見る</div>")
                    .append("</div></").append(tag).append(">");
            return html.toString();
        } catch (IllegalArgumentException | ContentScrapingException e) {
            log.warn("[amazon]の展開に失敗したため通常のリンクにフォールバックします: url={}, error={}",
                    rawUrl, e.getMessage());
            return fallbackLink(rawUrl, isProductionSite);
        }
    }

    private String fallbackLink(String rawUrl, boolean isProductionSite) {
        String escaped = HtmlUtils.htmlEscape(rawUrl);
        if (!isProductionSite || !isHttpUrl(rawUrl)) {
            // 非本番サイトではリンク化せずプレーンテキストとして出力する(issue #389)。
            // http/https以外(javascript:等)は本番サイトでもリンク化しない。
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

    /**
     * 日本円であることを明示する(issue #347)。Amazon.co.jpの価格表示は既に円建てだが、
     * セレクタによっては通貨記号が付かない場合があるため、¥/円のいずれも無ければ全角の￥を補う
     * (Amazon.co.jpの価格表示自体が全角￥を使うため、それに揃える)。
     * 半角¥はHtmlUtils.htmlEscapeでHTML実体参照(&yen;)に変換され、生の文字として出力されなくなるため使わない。
     */
    private String toYenPrice(String rawPrice) {
        String price = nullToEmpty(rawPrice).trim();
        if (price.isEmpty() || price.startsWith("¥") || price.startsWith("￥") || price.endsWith("円")) {
            return price;
        }
        return "￥" + price;
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
