package com.letsblog.content.service;

import com.letsblog.content.client.ProjectBridgeClient;
import com.letsblog.content.contentcache.ContentCacheService;
import com.letsblog.content.contentcache.ContentScrapingException;
import com.letsblog.content.dto.ContentCacheResponse;
import com.letsblog.content.dto.TagDesignColors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown本文中の組み込みタグ `[blogcard URL]` を、OGP情報を使ったブログカードHTMLに展開する。
 * legacy-apiのBlogCardTagRenderServiceと同じ実装(issue #576でcontent-serviceへ移管)。
 * CustomTagRenderService/PlantUmlEmbedServiceと同様、flexmarkの拡張機構は使わず
 * Markdown文字列への正規表現前処理として実装する。
 * URL取得・スクレイピングに失敗した場合や無効なURLの場合は通常のリンク(またはプレーンテキスト)に
 * フォールバックし、記事全体のレンダリングは失敗させない。
 * スクレイピング結果(タイトル・説明・URL等)は対象サイトが自由に設定できる非信頼な文字列のため、
 * HTML出力に含める際は必ずエスケープし、URLはhttp/https以外を許可しない(XSS対策)。
 * カードの配色はプロジェクトごとのデザイン設定(#150、tag_design_settings)に従う。
 * 同テーブルはlegacy-apiに残るドメインのため、{@link ProjectBridgeClient}経由の内部ブリッジで解決する。
 */
@Service
@Slf4j
public class BlogCardTagRenderService {

    private static final Pattern BLOGCARD_TAG_PATTERN =
            Pattern.compile("\\[blogcard\\s+(\\S+)\\s*]", Pattern.CASE_INSENSITIVE);

    private static final String CARD_CLASS_ATTR = "class=\"lb-blogcard\"";

    private final ContentCacheService contentCacheService;
    private final ProjectBridgeClient projectBridgeClient;
    private final CurrentActorService currentActorService;

    public BlogCardTagRenderService(
            ContentCacheService contentCacheService, ProjectBridgeClient projectBridgeClient,
            CurrentActorService currentActorService) {
        this.contentCacheService = contentCacheService;
        this.projectBridgeClient = projectBridgeClient;
        this.currentActorService = currentActorService;
    }

    public String render(String markdown, Long projectId) {
        if (markdown == null || markdown.isEmpty()) {
            return markdown;
        }

        Matcher matcher = BLOGCARD_TAG_PATTERN.matcher(markdown);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String rawUrl = matcher.group(1);
            boolean block = EmbedMarker.isStandalone(markdown, matcher.start(), matcher.end());
            matcher.appendReplacement(result, Matcher.quoteReplacement(renderCard(rawUrl, projectId, block)));
        }
        matcher.appendTail(result);

        // 取得失敗で全てプレーンリンクにフォールバックした場合、使われないCSSを本文に混入させない
        if (result.indexOf(CARD_CLASS_ATTR) < 0) {
            return result.toString();
        }
        TagDesignColors colors = projectBridgeClient.toColors(projectBridgeClient.resolveTagDesign(
                projectId, "BLOGCARD", currentActorService.getAuthorizationHeader()));
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
        return ".lb-blogcard{display:flex;align-items:stretch;border:1px solid #e0e0e0;"
                + "border-left:4px solid " + colors.accentColor() + ";border-radius:8px;overflow:hidden;"
                + "text-decoration:none;background:" + colors.backgroundColor() + ";color:" + colors.textColor()
                + ";max-width:100%;margin:1em 0;transition:box-shadow .15s ease;}"
                + ".lb-blogcard:hover{box-shadow:0 2px 8px rgba(0,0,0,.12);}"
                + ".lb-blogcard-thumb{flex:0 0 120px;background-size:cover;background-position:center;"
                + "background-color:#f2f2f2;}"
                + ".lb-blogcard-body{flex:1 1 auto;min-width:0;padding:12px 16px;display:flex;"
                + "flex-direction:column;gap:4px;}"
                + ".lb-blogcard-title{font-weight:600;font-size:1em;overflow:hidden;text-overflow:ellipsis;"
                + "white-space:nowrap;}"
                + ".lb-blogcard-description{font-size:.875em;opacity:.75;overflow:hidden;display:-webkit-box;"
                + "-webkit-line-clamp:2;-webkit-box-orient:vertical;}"
                + ".lb-blogcard-site{font-size:.75em;opacity:.6;margin-top:auto;}";
    }

    private String renderCard(String rawUrl, Long projectId, boolean block) {
        try {
            ContentCacheResponse response = contentCacheService.resolve(rawUrl);
            Map<String, String> data = response.data();

            String href = sanitizeUrl(data.get("url"), rawUrl);
            String imageUrl = sanitizeUrl(data.get("imageUrl"), null);
            String title = HtmlUtils.htmlEscape(firstNonBlank(data.get("title"), href));
            String description = HtmlUtils.htmlEscape(nullToEmpty(data.get("description")));
            String siteName = HtmlUtils.htmlEscape(nullToEmpty(data.get("siteName")));
            String escapedHref = HtmlUtils.htmlEscape(href);
            String escapedImageUrl = imageUrl == null ? "" : HtmlUtils.htmlEscape(imageUrl);

            // 表示時にプラグインが差し込み直すためのデータ(エスケープ前の生の値)。プラグイン側でエスケープする。
            Map<String, String> markerData = new LinkedHashMap<>();
            markerData.put("title", firstNonBlank(data.get("title"), href));
            markerData.put("description", nullToEmpty(data.get("description")));
            markerData.put("siteName", nullToEmpty(data.get("siteName")));
            markerData.put("url", href);
            markerData.put("imageUrl", imageUrl == null ? "" : imageUrl);

            String customTemplate = projectBridgeClient.resolveTagDesign(
                    projectId, "BLOGCARD", currentActorService.getAuthorizationHeader()).htmlTemplate();
            if (customTemplate != null) {
                return EmbedMarker.wrap("BLOGCARD", markerData, EmbedTagTemplateRenderer.render(customTemplate, Map.of(
                        "title", title,
                        "description", description,
                        "siteName", siteName,
                        "url", escapedHref,
                        "imageUrl", escapedImageUrl)), block);
            }

            StringBuilder html = new StringBuilder();
            html.append("<a class=\"lb-blogcard\" href=\"").append(escapedHref)
                    .append("\" target=\"_blank\" rel=\"noopener noreferrer\">");
            if (imageUrl != null) {
                html.append("<div class=\"lb-blogcard-thumb\" style=\"background-image:url('")
                        .append(escapedImageUrl).append("')\"></div>");
            }
            html.append("<div class=\"lb-blogcard-body\">")
                    .append("<div class=\"lb-blogcard-title\">").append(title).append("</div>")
                    .append("<div class=\"lb-blogcard-description\">").append(description).append("</div>")
                    .append("<div class=\"lb-blogcard-site\">").append(siteName).append("</div>")
                    .append("</div></a>");
            return EmbedMarker.wrap("BLOGCARD", markerData, html.toString(), block);
        } catch (IllegalArgumentException | ContentScrapingException e) {
            log.warn("[blogcard]の展開に失敗したため通常のリンクにフォールバックします: url={}, error={}",
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
        return "<a href=\"" + escaped + "\" target=\"_blank\" rel=\"noopener noreferrer\">" + escaped + "</a>";
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
