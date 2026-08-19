package com.letsblog.api.service;

import com.letsblog.api.domain.ChartType;
import com.letsblog.api.markdown.MarkdownTable;
import com.letsblog.api.markdown.MarkdownTableParseException;
import com.letsblog.api.markdown.MarkdownTableParser;
import com.letsblog.api.render.RechartsChartConfig;
import com.letsblog.api.render.RechartsRenderException;
import com.letsblog.api.render.RechartsRenderer;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown本文中の組み込みタグ`[recharts type="..." ...]`〜`[/recharts]`(本文はMarkdownテーブル)を、
 * Recharts(React)でサーバーサイドレンダリングした静的なチャートHTML(SVG+凡例)に展開する(Issue #340)。
 *
 * BlogCard/Amazonタグと異なり、記法・属性・表データの誤りは「取得失敗時に静かにフォールバック」ではなく
 * {@link InvalidRechartsTagException}として投げる。呼び出し側の扱いが異なる点に注意:
 * - プレビュー({@link ArticlePreviewService#renderHtml})はこれを捕捉し、レンダリングを中止して
 *   エラーメッセージのみを表示する。
 * - 投稿({@link PostPublishService#publish})はこれを未捕捉のまま伝播させ、投稿自体を拒否する
 *   ({@link com.letsblog.api.config.GlobalExceptionHandler}が400として返す)。
 */
@Service
public class RechartsTagRenderService {

    private static final Pattern RECHARTS_BLOCK_PATTERN =
            Pattern.compile("\\[recharts([^\\]\\n]*)]\\r?\\n(.*?)\\[/recharts]",
                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR_PATTERN = Pattern.compile("([a-zA-Z][a-zA-Z0-9_-]*)=\"([^\"]*)\"");
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("^-?[0-9]+(\\.[0-9]+)?$");
    private static final Pattern WRAPPER_SIZE_STYLE_PATTERN =
            Pattern.compile("width:\\s*(\\d+)px;\\s*height:\\s*(\\d+)px;?");

    private static final int MAX_ROWS = 1000;
    private static final int MAX_COLUMNS = 10;
    private static final int DEFAULT_WIDTH = 700;
    private static final int DEFAULT_HEIGHT = 300;
    private static final int MIN_SIZE_PX = 100;
    private static final int MAX_SIZE_PX = 2000;

    /**
     * 系列へ割り当てる既定の配色。背景の明暗どちらでもある程度視認できるよう、
     * 極端に明るい/暗い色を避けた中彩度のカテゴリカルパレット(Tableau 10相当)を使う。
     */
    private static final List<String> DEFAULT_PALETTE = List.of(
            "#4e79a7", "#f28e2b", "#e15759", "#76b7b2", "#59a14f",
            "#edc948", "#b07aa1", "#ff9da7", "#9c755f", "#bab0ac");

    private final RechartsRenderer rechartsRenderer;

    public RechartsTagRenderService(RechartsRenderer rechartsRenderer) {
        this.rechartsRenderer = rechartsRenderer;
    }

    /**
     * @throws InvalidRechartsTagException [recharts]タグの記法・属性・表データが不正な場合、
     *                                      またはレンダリングに失敗した場合
     */
    public String render(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return markdown;
        }
        Matcher matcher = RECHARTS_BLOCK_PATTERN.matcher(markdown);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            Map<String, String> attrs = parseAttrs(matcher.group(1));
            String body = matcher.group(2);
            String replacement = renderChart(attrs, body);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String renderChart(Map<String, String> attrs, String body) {
        ChartType type = parseType(attrs);
        MarkdownTable table = parseTable(body);
        validateTableSize(table);

        String xAxisKey = type == ChartType.PIE ? null : requireXAxis(attrs, table, type);
        List<String> seriesKeys = resolveSeriesKeys(type, table, xAxisKey);
        validateNumericColumns(type, table, seriesKeys);
        List<Map<String, Object>> data = buildData(type, table, seriesKeys);

        int width = parseSize(attrs.get("width"), DEFAULT_WIDTH, "width");
        int height = parseSize(attrs.get("height"), DEFAULT_HEIGHT, "height");
        boolean stacked = parseBoolean(attrs.get("stacked"));
        boolean responsive = attrs.containsKey("responsive") ? parseBoolean(attrs.get("responsive")) : true;
        boolean dark = "dark".equalsIgnoreCase(attrs.get("theme"));
        String textColor = dark ? "#e0e0e0" : "#333333";
        String gridColor = dark ? "#555555" : "#e0e0e0";
        String yAxisLabel = attrs.get("yAxis");

        RechartsChartConfig config = new RechartsChartConfig(
                type.name().toLowerCase(Locale.ROOT), data, xAxisKey, seriesKeys, DEFAULT_PALETTE,
                stacked, width, height, textColor, gridColor,
                type == ChartType.PIE ? null : yAxisLabel);

        String chartHtml;
        try {
            chartHtml = rechartsRenderer.render(config);
        } catch (RechartsRenderException e) {
            throw new InvalidRechartsTagException(e.getMessage());
        }
        if (responsive) {
            chartHtml = makeResponsive(chartHtml);
        }
        return wrap(attrs.get("title"), chartHtml);
    }

    private ChartType parseType(Map<String, String> attrs) {
        String type = attrs.get("type");
        if (type == null || type.isBlank()) {
            throw new InvalidRechartsTagException("type属性は必須です(bar/line/area/pieのいずれかを指定してください)");
        }
        try {
            return ChartType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidRechartsTagException(
                    "type属性の値が不正です: \"" + type + "\"(bar/line/area/pieのいずれかを指定してください)");
        }
    }

    private MarkdownTable parseTable(String body) {
        try {
            return MarkdownTableParser.parse(body);
        } catch (MarkdownTableParseException e) {
            throw new InvalidRechartsTagException(e.getMessage());
        }
    }

    private void validateTableSize(MarkdownTable table) {
        if (table.headers().size() > MAX_COLUMNS) {
            throw new InvalidRechartsTagException(
                    "カラム数が上限(" + MAX_COLUMNS + ")を超えています(" + table.headers().size() + "列)");
        }
        if (table.rows().size() > MAX_ROWS) {
            throw new InvalidRechartsTagException(
                    "データ行数が上限(" + MAX_ROWS + ")を超えています(" + table.rows().size() + "行)");
        }
    }

    private String requireXAxis(Map<String, String> attrs, MarkdownTable table, ChartType type) {
        String xAxisKey = attrs.get("xAxis");
        if (xAxisKey == null || xAxisKey.isBlank()) {
            throw new InvalidRechartsTagException(
                    "xAxis属性は必須です(type=\"" + type.name().toLowerCase(Locale.ROOT) + "\"の場合)");
        }
        xAxisKey = xAxisKey.trim();
        if (!table.headers().contains(xAxisKey)) {
            throw new InvalidRechartsTagException(
                    "xAxis属性で指定された列 \"" + xAxisKey + "\" がテーブルの見出しに見つかりません");
        }
        return xAxisKey;
    }

    private List<String> resolveSeriesKeys(ChartType type, MarkdownTable table, String xAxisKey) {
        List<String> headers = table.headers();
        if (type == ChartType.PIE) {
            if (headers.size() != 2) {
                throw new InvalidRechartsTagException(
                        "円グラフ(type=\"pie\")はカラム数が2(項目名, 数値)である必要があります(" + headers.size() + "列)");
            }
            return headers;
        }
        List<String> seriesKeys = new ArrayList<>(headers);
        seriesKeys.remove(xAxisKey);
        if (seriesKeys.isEmpty()) {
            throw new InvalidRechartsTagException("xAxis以外のデータ列がありません");
        }
        return seriesKeys;
    }

    private void validateNumericColumns(ChartType type, MarkdownTable table, List<String> seriesKeys) {
        List<String> headers = table.headers();
        // 円グラフはseriesKeys[0]が項目名(数値チェック対象外)、seriesKeys[1]が数値列。
        List<String> numericColumns = type == ChartType.PIE ? List.of(seriesKeys.get(1)) : seriesKeys;
        for (int rowIndex = 0; rowIndex < table.rows().size(); rowIndex++) {
            List<String> row = table.rows().get(rowIndex);
            for (String column : numericColumns) {
                String rawValue = row.get(headers.indexOf(column));
                if (!isNumeric(rawValue)) {
                    throw new InvalidRechartsTagException(
                            "数値が無効です(行" + (rowIndex + 1) + " 列\"" + column + "\": \"" + rawValue + "\")");
                }
            }
        }
    }

    private List<Map<String, Object>> buildData(ChartType type, MarkdownTable table, List<String> seriesKeys) {
        List<String> headers = table.headers();
        List<String> numericColumns = type == ChartType.PIE ? List.of(seriesKeys.get(1)) : seriesKeys;
        List<Map<String, Object>> data = new ArrayList<>(table.rows().size());
        for (List<String> row : table.rows()) {
            Map<String, Object> record = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                String column = headers.get(i);
                String rawValue = row.get(i);
                record.put(column, numericColumns.contains(column) ? parseNumeric(rawValue) : rawValue);
            }
            data.add(record);
        }
        return data;
    }

    private boolean isNumeric(String rawValue) {
        String normalized = rawValue.replace(",", "").trim();
        return !normalized.isEmpty() && NUMERIC_PATTERN.matcher(normalized).matches();
    }

    private double parseNumeric(String rawValue) {
        return Double.parseDouble(rawValue.replace(",", "").trim());
    }

    private int parseSize(String raw, int defaultValue, String attrName) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        String trimmed = raw.trim();
        if (trimmed.endsWith("%")) {
            // パーセント指定はオフスクリーンでの固定サイズレンダリングでは意味を持たないため、既定値を使う。
            return defaultValue;
        }
        String numeric = trimmed.endsWith("px") ? trimmed.substring(0, trimmed.length() - 2) : trimmed;
        int value;
        try {
            value = Integer.parseInt(numeric.trim());
        } catch (NumberFormatException e) {
            throw new InvalidRechartsTagException(attrName + "属性の値が不正です: \"" + raw + "\"");
        }
        if (value < MIN_SIZE_PX || value > MAX_SIZE_PX) {
            throw new InvalidRechartsTagException(
                    attrName + "属性は" + MIN_SIZE_PX + "〜" + MAX_SIZE_PX + "の範囲で指定してください(指定値: " + value + ")");
        }
        return value;
    }

    private boolean parseBoolean(String raw) {
        return "true".equalsIgnoreCase(raw);
    }

    /**
     * レンダリング結果(固定px幅高さの`.recharts-wrapper`)を、コンテナ幅に追従して縮小できるよう
     * CSSの`aspect-ratio`を使ったフルード指定へ書き換える(responsive属性、既定true)。
     */
    private String makeResponsive(String wrapperHtml) {
        Matcher sizeMatcher = WRAPPER_SIZE_STYLE_PATTERN.matcher(wrapperHtml);
        if (!sizeMatcher.find()) {
            return wrapperHtml;
        }
        String width = sizeMatcher.group(1);
        String height = sizeMatcher.group(2);
        String replacement = "width: 100%; max-width: " + width + "px; height: auto; aspect-ratio: "
                + width + " / " + height + ";";
        return sizeMatcher.replaceFirst(Matcher.quoteReplacement(replacement));
    }

    private String wrap(String title, String chartHtml) {
        StringBuilder html = new StringBuilder("<div class=\"lb-recharts\">");
        if (title != null && !title.isBlank()) {
            html.append("<p class=\"lb-recharts-title\" style=\"font-weight:600;margin:0 0 8px;\">")
                    .append(HtmlUtils.htmlEscape(title))
                    .append("</p>");
        }
        html.append(chartHtml).append("</div>");
        return html.toString();
    }

    private Map<String, String> parseAttrs(String attrPart) {
        Map<String, String> attrs = new LinkedHashMap<>();
        if (attrPart == null) {
            return attrs;
        }
        Matcher matcher = ATTR_PATTERN.matcher(attrPart);
        while (matcher.find()) {
            attrs.put(matcher.group(1), matcher.group(2));
        }
        return attrs;
    }
}
