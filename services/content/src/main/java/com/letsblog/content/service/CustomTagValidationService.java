package com.letsblog.content.service;

import com.letsblog.content.dto.ValidationError;
import com.letsblog.content.dto.ValidationResult;
import com.letsblog.content.dto.ValidationWarning;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class CustomTagValidationService {

    private static final Pattern SCRIPT_TAG_PATTERN = Pattern.compile("(?i)<\\s*script[^>]*>.*?</\\s*script\\s*>");
    private static final Pattern EVENT_HANDLER_PATTERN = Pattern.compile("(?i)\\s(on\\w+)\\s*=");
    private static final Pattern JAVASCRIPT_PROTOCOL_PATTERN = Pattern.compile("(?i)javascript\\s*:");

    public ValidationResult validate(String htmlTemplate, String cssContent) {
        List<ValidationError> errors = new ArrayList<>();
        List<ValidationWarning> warnings = new ArrayList<>();

        // HTML検証
        validateHtml(htmlTemplate, errors, warnings);

        // CSS検証
        if (cssContent != null && !cssContent.isBlank()) {
            validateCss(cssContent, errors, warnings);
        }

        return ValidationResult.invalid(errors, warnings);
    }

    private void validateHtml(String htmlTemplate, List<ValidationError> errors, List<ValidationWarning> warnings) {
        if (htmlTemplate == null || htmlTemplate.isBlank()) {
            errors.add(ValidationError.of("empty-html", "HTMLテンプレートが空です", "error"));
            return;
        }

        // スクリプトタグのチェック
        if (SCRIPT_TAG_PATTERN.matcher(htmlTemplate).find()) {
            errors.add(ValidationError.of(
                "script-tag-detected",
                "<script>タグは許可されていません。セキュリティ上の理由から、外部スクリプトやインラインスクリプトは実行できません",
                "error"
            ));
        }

        // イベントハンドラーのチェック
        if (EVENT_HANDLER_PATTERN.matcher(htmlTemplate).find()) {
            errors.add(ValidationError.of(
                "event-handler-detected",
                "on* 属性（onclick, onload等）は許可されていません",
                "error"
            ));
        }

        // JavaScriptプロトコルのチェック
        if (JAVASCRIPT_PROTOCOL_PATTERN.matcher(htmlTemplate).find()) {
            errors.add(ValidationError.of(
                "javascript-protocol-detected",
                "javascript: プロトコルは許可されていません",
                "error"
            ));
        }

        // HTMLパース検証
        try {
            Jsoup.parseBodyFragment(htmlTemplate);

            // {{content}} プレースホルダーのチェック
            if (!htmlTemplate.contains("{{content}}")) {
                warnings.add(ValidationWarning.of(
                    "missing-content-placeholder",
                    "{{content}} プレースホルダーが見つかりません。投稿本文がこのコンポーネントに埋め込まれません。"
                ));
            }
        } catch (Exception e) {
            errors.add(ValidationError.of(
                "html-parse-error",
                "HTMLの解析に失敗しました: " + e.getMessage(),
                "error"
            ));
        }
    }

    private void validateCss(String cssContent, List<ValidationError> errors, List<ValidationWarning> warnings) {
        if (cssContent == null || cssContent.isBlank()) {
            return;
        }

        String[] lines = cssContent.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            // コメント行をスキップ
            if (line.startsWith("/*") || line.startsWith("//") || line.isEmpty()) {
                continue;
            }

            // CSSルールブロックのチェック
            if (line.contains("{") && !line.contains("}")) {
                validateCssRule(line, i + 1, errors);
            }
        }

        // 基本的な CSS 構文チェック
        if (!cssContent.contains("{") || !cssContent.contains("}")) {
            warnings.add(ValidationWarning.of(
                "incomplete-css-rules",
                "CSSルールが不完全な可能性があります。セレクタ { プロパティ: 値; } の形式を確認してください。"
            ));
        }

        // CSSインジェクション検出
        if (cssContent.contains("expression(") || cssContent.contains("behavior:")) {
            errors.add(ValidationError.of(
                "css-injection-detected",
                "IE固有のCSS機能（expression, behavior等）は許可されていません",
                "error"
            ));
        }
    }

    private void validateCssRule(String line, int lineNumber, List<ValidationError> errors) {
        // セレクタの基本的なバリデーション - CSSルールが { で始まるキーワード（@media, @keyframes等）でないこと確認
        // 実際のセレクタ内容は多様なため、単純に { が存在することと基本的な構文を確認する
        if (line.startsWith("@")) {
            // @ルール（@media, @keyframes等）は許可
            return;
        }

        // セレクタが空でなく、基本的な形式を持つことを確認
        String selector = line.substring(0, line.indexOf("{")).trim();
        if (selector.isEmpty()) {
            errors.add(ValidationError.of(
                "invalid-css-selector",
                "無効なCSSセレクタの形式です。セレクタ { の形式を確認してください。",
                lineNumber,
                "error"
            ));
        }
    }

    public String sanitizeHtml(String htmlTemplate) {
        if (htmlTemplate == null) {
            return "";
        }

        // スクリプトタグを削除
        String sanitized = SCRIPT_TAG_PATTERN.matcher(htmlTemplate).replaceAll("");

        // イベントハンドラー属性を削除
        sanitized = sanitized.replaceAll("(?i)\\s+on\\w+\\s*=\\s*['\"][^'\"]*['\"]", "");
        sanitized = sanitized.replaceAll("(?i)\\s+on\\w+\\s*=\\s*\\S+", "");

        // JavaScriptプロトコルを削除
        sanitized = sanitized.replaceAll("(?i)href\\s*=\\s*['\"]javascript:", "href=\"#");

        return sanitized;
    }
}
