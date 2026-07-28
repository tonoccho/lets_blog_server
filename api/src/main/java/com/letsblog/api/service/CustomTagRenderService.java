package com.letsblog.api.service;

import com.letsblog.api.domain.CustomTag;
import com.letsblog.api.repository.CustomTagRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Markdown本文中の独自ショートコード記法(`:::tagname key="value"` 〜 `:::`)を
 * 管理画面で定義されたHTMLテンプレートに展開する。PlantUmlEmbedServiceと同様、
 * flexmarkの拡張機構は使わずMarkdown文字列への正規表現前処理として実装する。
 * タグに紐づくCSSが設定されている場合、実際に使用されたタグの分だけ
 * (同じタグが複数回使われても重複させず)本文冒頭に `<style>` ブロックとして差し込む。
 */
@Service
public class CustomTagRenderService {

    private static final Pattern CUSTOM_TAG_PATTERN =
            Pattern.compile(":::([a-zA-Z][a-zA-Z0-9_-]*)([^\\n]*)\\n(.*?):::", Pattern.DOTALL);
    private static final Pattern ATTR_PATTERN =
            Pattern.compile("([a-zA-Z][a-zA-Z0-9_-]*)=\"([^\"]*)\"");
    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("\\{\\{attr:([a-zA-Z0-9_-]+)}}");

    private final CustomTagRepository customTagRepository;

    public CustomTagRenderService(CustomTagRepository customTagRepository) {
        this.customTagRepository = customTagRepository;
    }

    public String render(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return markdown;
        }

        Map<String, CustomTag> tagsByName = customTagRepository.findAll().stream()
                .collect(Collectors.toMap(CustomTag::getTagName, tag -> tag, (a, b) -> a));

        if (tagsByName.isEmpty()) {
            return markdown;
        }

        Set<CustomTag> usedTags = new LinkedHashSet<>();
        Matcher matcher = CUSTOM_TAG_PATTERN.matcher(markdown);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String tagName = matcher.group(1);
            String attrPart = matcher.group(2);
            String content = matcher.group(3);

            CustomTag tag = tagsByName.get(tagName);
            String replacement;
            if (tag == null) {
                replacement = matcher.group(0);
            } else {
                usedTags.add(tag);
                replacement = applyTemplate(tag.getHtmlTemplate(), content, parseAttrs(attrPart));
            }

            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        String styleBlock = buildStyleBlock(usedTags);
        return styleBlock.isEmpty() ? result.toString() : styleBlock + "\n\n" + result;
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
