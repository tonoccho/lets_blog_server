package com.letsblog.publishing.service;

import com.letsblog.publishing.dto.PullRequestArticleResponse.FrontMatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * article.mdをfront matterと本文へ分離する(issue #1338)。
 *
 * <p>拡張側{@code apps/extension/src/frontMatter.ts}の{@code parseArticle}(gray-matter +
 * {@code normalizeCategoryKey} + {@code stripLegacyWordPressIdKeys})と挙動を揃える。揃っていないと、
 * 拡張から投稿した場合とレビュー経由で投稿した場合で結果が変わる。
 * <ul>
 *   <li>先頭が{@code ---}の行で始まり、閉じの{@code ---}の行があるときだけfront matterとみなす。
 *   閉じ区切り直後の1つの改行は本文に含めない。</li>
 *   <li>単数形{@code category}は、{@code categories}が未設定または空のとき{@code categories}へ寄せ、
 *   {@code category}キー自体は常に消す。</li>
 *   <li>廃止された{@code wp_post_id}/{@code wp_post_url}/{@code wp_post_ids}は取り除く。</li>
 * </ul>
 */
public final class ArticleFrontMatterParser {

    private static final String OPEN = "---";
    private static final Pattern OPENING_LINE = Pattern.compile("^---[ \\t]*\\r?\\n");
    private static final Pattern CLOSING_LINE = Pattern.compile("(?m)^---[ \\t]*(?:\\r?\\n|$)");
    private static final List<String> LEGACY_WORDPRESS_KEYS = List.of("wp_post_id", "wp_post_url", "wp_post_ids");

    private ArticleFrontMatterParser() {
    }

    /** 分離した記事。{@code data}は正規化後のfront matter全体(未知のキーも保持)。 */
    public record ParsedArticle(Map<String, Object> data, String content) {

        /** 投稿が使う項目だけを型付きで取り出す。 */
        public FrontMatter frontMatter() {
            return new FrontMatter(
                    asString(data.get("title")),
                    asString(data.get("slug")),
                    asString(data.get("status")),
                    asStringList(data.get("categories")),
                    asStringList(data.get("tags")),
                    asString(data.get("featured_image")),
                    asString(data.get("publish_scheduled_at")));
        }
    }

    public static ParsedArticle parse(String text) {
        String source = text.startsWith("﻿") ? text.substring(1) : text;
        Matcher opening = OPENING_LINE.matcher(source);
        if (!opening.find()) {
            return new ParsedArticle(new LinkedHashMap<>(), source);
        }
        String rest = source.substring(opening.end());
        Matcher closing = CLOSING_LINE.matcher(rest);
        if (!closing.find()) {
            return new ParsedArticle(new LinkedHashMap<>(), source);
        }
        Map<String, Object> data = load(rest.substring(0, closing.start()));
        normalizeCategoryKey(data);
        LEGACY_WORDPRESS_KEYS.forEach(data::remove);
        return new ParsedArticle(data, rest.substring(closing.end()));
    }

    private static Map<String, Object> load(String yaml) {
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        } catch (YAMLException e) {
            throw new PullRequestArticleException(PullRequestArticleException.Kind.INVALID,
                    "article.mdのfront matterをYAMLとして読めません: " + e.getMessage(), e);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (loaded == null) {
            return data;
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new PullRequestArticleException(PullRequestArticleException.Kind.INVALID,
                    "article.mdのfront matterはキーと値の組(YAMLのマッピング)で書いてください");
        }
        map.forEach((k, v) -> data.put(String.valueOf(k), v));
        return data;
    }

    private static void normalizeCategoryKey(Map<String, Object> data) {
        if (!data.containsKey("category")) {
            return;
        }
        Object legacy = data.remove("category");
        if (isEmpty(data.get("categories")) && legacy != null) {
            data.put("categories", legacy instanceof List<?> ? legacy : String.valueOf(legacy));
        }
    }

    private static boolean isEmpty(Object value) {
        return value == null
                || value instanceof List<?> list && list.isEmpty()
                || value instanceof String string && string.isEmpty();
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Date date) {
            // 引用符なしのISO日時はYAMLのtimestampとしてDateになる。拡張の検証は文字列を要求するため戻す。
            return date.toInstant().toString();
        }
        return value.toString();
    }

    private static List<String> asStringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value == null) {
            return result;
        }
        if (value instanceof List<?> list) {
            for (Object element : list) {
                String converted = asString(element);
                if (converted != null) {
                    result.add(converted);
                }
            }
            return result;
        }
        result.add(asString(value));
        return result;
    }
}
