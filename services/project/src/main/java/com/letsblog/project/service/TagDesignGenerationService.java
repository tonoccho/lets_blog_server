package com.letsblog.project.service;

import com.letsblog.project.client.AiGenerationClient;
import com.letsblog.project.domain.EmbedTagType;
import com.letsblog.project.dto.GenerateTagDesignResponse;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [toc]/[blogcard]/[amazon] 組み込みタグのデザイン(CSS/必要ならHTMLテンプレート)をAIで生成する(issue #183)。
 * カスタムタグのAI生成(content-serviceのCustomTagGenerationService)と異なり、生成結果は即座に保存せず、
 * 呼び出し元(画面)がプレビューしたうえで既存のTagDesignSettingService.save()に渡す。
 * 組み込みタグはプロジェクト+タグ種別ごとに1件のみのupsert対象であり、
 * カスタムタグのような名前衝突は起きないため、この非永続の生成→適用というUXで十分。
 */
@Service
public class TagDesignGenerationService {

    private static final Pattern HTML_PATTERN = Pattern.compile("```html\\s*\\n([\\s\\S]*?)\\n```");
    private static final Pattern CSS_PATTERN = Pattern.compile("```css\\s*\\n([\\s\\S]*?)\\n```");

    private record TagContext(String label, String syntax, String cssClassName, String placeholders) {
    }

    private static final Map<EmbedTagType, TagContext> CONTEXT = Map.of(
            EmbedTagType.TOC, new TagContext("目次", "[toc]", ".lb-toc-list", "{{toc}}"),
            EmbedTagType.BLOGCARD, new TagContext(
                    "ブログカード", "[blogcard URL]", ".lb-blogcard",
                    "{{title}}, {{description}}, {{siteName}}, {{url}}, {{imageUrl}}"),
            EmbedTagType.AMAZON, new TagContext(
                    "Amazon商品カード", "[amazon URL]", ".lb-amazon-card",
                    "{{productName}}, {{price}}, {{productUrl}}, {{imageUrl}}"));

    private final AiGenerationClient aiGenerationClient;

    public TagDesignGenerationService(AiGenerationClient aiGenerationClient) {
        this.aiGenerationClient = aiGenerationClient;
    }

    public GenerateTagDesignResponse generate(
            Long projectId, EmbedTagType tagType, String userPrompt, String currentHtmlTemplate) {
        // issue #574: プロジェクトの選択中モデル解決も含めai-serviceへ委譲する。
        String response = aiGenerationClient.generate(
                projectId, buildPrompt(tagType, userPrompt, currentHtmlTemplate), null);

        String cssContent = extract(CSS_PATTERN, response);
        String htmlTemplate = extract(HTML_PATTERN, response);

        if (cssContent.isBlank()) {
            throw new InvalidCustomTagContentException(
                    "LLMレスポンスからCSSを抽出できませんでした。```css ... ``` の形式で返されることを確認してください。");
        }

        return new GenerateTagDesignResponse(htmlTemplate, cssContent);
    }

    private String buildPrompt(EmbedTagType tagType, String userPrompt, String currentHtmlTemplate) {
        TagContext context = CONTEXT.get(tagType);
        String htmlContext = currentHtmlTemplate == null || currentHtmlTemplate.isBlank()
                ? "(未設定。標準テンプレートを使用中です)"
                : currentHtmlTemplate;

        return String.format(
                """
                組み込みタグ「%s」(%s)の見た目をデザインするためのCSSを生成してください。

                要件:
                1. CSSは```css...```で囲まれた形式で出力してください
                2. 対象要素には必ず次のCSSクラス名を使用してください: %s
                3. レスポンシブデザインに対応してください
                4. HTML構造の変更が必要な場合のみ、```html...```で囲まれた形式でHTMLテンプレートも出力してください。
                   不要な場合はHTMLブロックを省略してください
                5. HTMLを出力する場合は、次のプレースホルダのみを使用してください(独自の変数は使えません): %s

                現在のHTMLテンプレート(参考。変更不要ならそのまま維持されます):
                %s

                ユーザーのリクエスト:
                %s
                """,
                context.label(), context.syntax(), context.cssClassName(), context.placeholders(),
                htmlContext, userPrompt);
    }

    private String extract(Pattern pattern, String response) {
        Matcher matcher = pattern.matcher(response);
        return matcher.find() ? matcher.group(1).strip() : "";
    }
}
