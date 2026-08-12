package com.letsblog.api.service;

import com.letsblog.api.domain.CustomTag;
import com.letsblog.api.domain.CustomTagFormat;
import com.letsblog.api.markdown.MarkdownRenderer;
import com.letsblog.api.repository.CustomTagRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * タグに紐づくCSSは本文には差し込まない(issue #165)。CSSはCustomTagServiceの
 * 統合CSSダウンロード機能経由でのみ提供する。
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
    private final MarkdownRenderer markdownRenderer;

    public CustomTagRenderService(CustomTagRepository customTagRepository, MarkdownRenderer markdownRenderer) {
        this.customTagRepository = customTagRepository;
        this.markdownRenderer = markdownRenderer;
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

        String afterBlock = expand(markdown, BLOCK_TAG_PATTERN, CustomTagFormat.BLOCK, tagsByName);
        return expand(afterBlock, INLINE_TAG_PATTERN, CustomTagFormat.INLINE, tagsByName);
    }

    /**
     * markdown中からpatternに一致する箇所を探し、タグ名が登録済みかつ形式がrequiredFormatと
     * 一致するものだけをテンプレート展開する。一致しないものは元の記述のまま残す。
     */
    private String expand(String markdown, Pattern pattern, CustomTagFormat requiredFormat,
                           Map<String, CustomTag> tagsByName) {
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
                replacement = applyTemplate(tag.getHtmlTemplate(), content, parseAttrs(attrPart));
            }

            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    private String applyTemplate(String template, String content, Map<String, String> attrs) {
        String withContent = template.replace("{{content}}", renderContentMarkdown(content.trim()));

        Matcher placeholderMatcher = PLACEHOLDER_PATTERN.matcher(withContent);
        StringBuilder rendered = new StringBuilder();
        while (placeholderMatcher.find()) {
            String value = attrs.getOrDefault(placeholderMatcher.group(1), "");
            placeholderMatcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        placeholderMatcher.appendTail(rendered);

        return rendered.toString();
    }

    /**
     * {{content}}に差し込む前にMarkdownをHTMLへ変換する。render()はテンプレート展開後の文字列を
     * 丸ごとMarkdownRendererに渡す2段構えだが、テンプレートのHTML(例: `<div>{{content}}</div>`)は
     * flexmarkにHTMLブロックと判定され、その内側はMarkdownとして解釈されない(CommonMarkの仕様)。
     * そのためcontent単体を先にHTML化してから埋め込む必要がある。
     * 1段落のみの内容は`<p>`で囲まずインライン要素だけを返す(タグテンプレート側の見た目を崩さないため)。
     */
    private String renderContentMarkdown(String content) {
        if (content.isEmpty()) {
            return content;
        }
        String html = markdownRenderer.render(content).strip();
        if (html.startsWith("<p>") && html.endsWith("</p>") && html.indexOf("<p>", 3) == -1) {
            return html.substring(3, html.length() - "</p>".length());
        }
        return html;
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
