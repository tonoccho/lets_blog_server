package com.letsblog.api.service;

import com.letsblog.api.domain.CustomTag;
import com.letsblog.api.domain.CustomTagFormat;
import com.letsblog.api.repository.CustomTagRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Markdown本文中の独自ショートコード記法(`[tagname key="value"]` 〜 `[/tagname]`)を
 * 管理画面で定義されたHTMLテンプレートに展開する。PlantUmlEmbedServiceと同様、
 * flexmarkの拡張機構は使わずMarkdown文字列への正規表現前処理として実装する。
 *
 * タグには管理画面でINLINE/BLOCKいずれかの形式が指定されており、本文中の実際の記述が
 * どちらの形にマッチするかで展開処理を分ける(判別ロジック)。
 * - BLOCK: 開始タグの直後に改行があり、複数行のコンテンツを囲む記法。
 *   `[tagname]\nコンテンツ\n[/tagname]`
 * - INLINE: 開始タグと終了タグが同一行にあり、文章中に埋め込む記法。
 *   `文章[tagname]コンテンツ[/tagname]文章`
 * 記述された形式とタグに設定された形式が一致しない場合は展開せずそのまま残す。
 *
 * タグに紐づくCSSが設定されている場合、実際に使用されたタグの分だけ
 * (同じタグが複数回使われても重複させず)本文冒頭に `<style>` ブロックとして差し込む。
 */
@Service
public class CustomTagRenderService {

    private static final Pattern BLOCK_TAG_PATTERN =
            Pattern.compile("\\[([a-zA-Z][a-zA-Z0-9_-]*)([^\\]\\n]*)]\\r?\\n(.*?)\\[/\\1]", Pattern.DOTALL);
    private static final Pattern INLINE_TAG_PATTERN =
            Pattern.compile("\\[([a-zA-Z][a-zA-Z0-9_-]*)([^\\]\\n]*)]([^\\n]*?)\\[/\\1]");
    private static final Pattern ATTR_PATTERN =
            Pattern.compile("([a-zA-Z][a-zA-Z0-9_-]*)=\"([^\"]*)\"");
    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("\\{\\{attr:([a-zA-Z0-9_-]+)}}");

    private final CustomTagRepository customTagRepository;

    public CustomTagRenderService(CustomTagRepository customTagRepository) {
        this.customTagRepository = customTagRepository;
    }

    public String render(String markdown) {
        return render(markdown, null);
    }

    /**
     * projectIdが指定されている場合、そのプロジェクトのタグ + グローバルタグ(project_id IS NULL)を
     * レンダリング対象とする。nullの場合はグローバルタグのみが対象。
     */
    public String render(String markdown, Long projectId) {
        if (markdown == null || markdown.isEmpty()) {
            return markdown;
        }

        List<CustomTag> candidates = projectId == null
                ? customTagRepository.findByProjectIdIsNull()
                : customTagRepository.findByProjectIdOrProjectIdIsNull(projectId);
        Map<String, CustomTag> tagsByName = candidates.stream()
                .collect(Collectors.toMap(CustomTag::getTagName, tag -> tag, (a, b) -> a));

        if (tagsByName.isEmpty()) {
            return markdown;
        }

        Set<CustomTag> usedTags = new LinkedHashSet<>();
        String afterBlock = expand(markdown, BLOCK_TAG_PATTERN, CustomTagFormat.BLOCK, tagsByName, usedTags);
        String afterInline = expand(afterBlock, INLINE_TAG_PATTERN, CustomTagFormat.INLINE, tagsByName, usedTags);

        String styleBlock = buildStyleBlock(usedTags);
        return styleBlock.isEmpty() ? afterInline : styleBlock + "\n\n" + afterInline;
    }

    /**
     * markdown中からpatternに一致する箇所を探し、タグ名が登録済みかつ形式がrequiredFormatと
     * 一致するものだけをテンプレート展開する。一致しないものは元の記述のまま残す。
     */
    private String expand(String markdown, Pattern pattern, CustomTagFormat requiredFormat,
                           Map<String, CustomTag> tagsByName, Set<CustomTag> usedTags) {
        Matcher matcher = pattern.matcher(markdown);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String tagName = matcher.group(1);
            String attrPart = matcher.group(2);
            String content = matcher.group(3);

            CustomTag tag = tagsByName.get(tagName);
            String replacement;
            if (tag == null || tag.getTagFormat() != requiredFormat) {
                replacement = matcher.group(0);
            } else {
                usedTags.add(tag);
                replacement = applyTemplate(tag.getHtmlTemplate(), content, parseAttrs(attrPart));
            }

            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    private String buildStyleBlock(Set<CustomTag> usedTags) {
        StringBuilder css = new StringBuilder();
        for (CustomTag tag : usedTags) {
            String cssContent = tag.getCssContent();
            if (cssContent != null && !cssContent.isBlank()) {
                css.append("<style>\n").append(cssContent.trim()).append("\n</style>\n\n");
            }
        }
        return css.toString().trim();
    }

    private String applyTemplate(String template, String content, Map<String, String> attrs) {
        String withContent = template.replace("{{content}}", content.trim());

        Matcher placeholderMatcher = PLACEHOLDER_PATTERN.matcher(withContent);
        StringBuilder rendered = new StringBuilder();
        while (placeholderMatcher.find()) {
            String value = attrs.getOrDefault(placeholderMatcher.group(1), "");
            placeholderMatcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        placeholderMatcher.appendTail(rendered);

        return rendered.toString();
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
