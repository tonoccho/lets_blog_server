package com.letsblog.api.service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 組み込みタグ([blogcard]/[amazon])のカスタムHTMLテンプレートで使う {{key}} プレースホルダを
 * 対応する値に置換する。CustomTagRenderServiceの{{attr:key}}と異なり、キーに接頭辞は付かない
 * (タグ種別ごとに固定のデータ項目のみを公開するため、任意属性の受け渡しは不要)。
 */
final class EmbedTagTemplateRenderer {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([a-zA-Z0-9_]+)}}");

    private EmbedTagTemplateRenderer() {
    }

    static String render(String template, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String value = values.getOrDefault(matcher.group(1), "");
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
