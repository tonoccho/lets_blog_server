package com.letsblog.content.service;

import com.letsblog.content.dto.ValidationResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #641: content-serviceの公開パイプライン中核向けテスト。CustomTagValidationServiceは
 * 管理画面から登録されるカスタムタグのHTMLテンプレート/CSSを検証する、XSS/CSSインジェクション対策の
 * 最終防衛線(#576でcontent-serviceへ移管)。悪意あるテンプレートを確実に検出できることを検証する。
 */
class CustomTagValidationServiceTest {

    private final CustomTagValidationService service = new CustomTagValidationService();

    @Test
    void validate_scriptタグを含むHTMLはerrorになる() {
        ValidationResult result = service.validate("<div>{{content}}<script>alert(1)</script></div>", null);

        assertFalse(result.isValid());
        assertTrue(result.errors().stream().anyMatch(e -> "script-tag-detected".equals(e.type())));
    }

    @Test
    void validate_onclickイベントハンドラを含むHTMLはerrorになる() {
        ValidationResult result = service.validate(
                "<div onclick=\"alert(1)\">{{content}}</div>", null);

        assertFalse(result.isValid());
        assertTrue(result.errors().stream().anyMatch(e -> "event-handler-detected".equals(e.type())));
    }

    @Test
    void validate_javascriptプロトコルを含むHTMLはerrorになる() {
        ValidationResult result = service.validate(
                "<a href=\"javascript:alert(1)\">{{content}}</a>", null);

        assertFalse(result.isValid());
        assertTrue(result.errors().stream().anyMatch(e -> "javascript-protocol-detected".equals(e.type())));
    }

    @Test
    void validate_HTMLが空文字の場合はerrorになる() {
        ValidationResult result = service.validate("", null);

        assertFalse(result.isValid());
        assertTrue(result.errors().stream().anyMatch(e -> "empty-html".equals(e.type())));
    }

    @Test
    void validate_contentプレースホルダーが無い場合はwarningになるがerrorにはならない() {
        ValidationResult result = service.validate("<div>本文なし</div>", null);

        assertTrue(result.isValid());
        assertTrue(result.warnings().stream().anyMatch(w -> "missing-content-placeholder".equals(w.type())));
    }

    @Test
    void validate_安全なHTMLとCSSはvalidになる() {
        ValidationResult result = service.validate(
                "<div class=\"card\">{{content}}</div>", ".card { color: red; }");

        assertTrue(result.isValid());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void validate_CSSのexpression関数はerrorになる() {
        ValidationResult result = service.validate(
                "<div>{{content}}</div>", ".card { width: expression(alert(1)); }");

        assertFalse(result.isValid());
        assertTrue(result.errors().stream().anyMatch(e -> "css-injection-detected".equals(e.type())));
    }

    @Test
    void validate_CSSのbehaviorプロパティはerrorになる() {
        ValidationResult result = service.validate(
                "<div>{{content}}</div>", ".card { behavior: url(xss.htc); }");

        assertFalse(result.isValid());
        assertTrue(result.errors().stream().anyMatch(e -> "css-injection-detected".equals(e.type())));
    }

    @Test
    void validate_無効なCSSセレクタはerrorになる() {
        // validateCssRuleは「{を含むが}を含まない行」だけを検査するため、開き括弧が単独の行になる
        // 複数行の記法で、セレクタが空であることを検証する。
        ValidationResult result = service.validate(
                "<div>{{content}}</div>", "{\ncolor: red;\n}");

        assertFalse(result.isValid());
        assertTrue(result.errors().stream().anyMatch(e -> "invalid-css-selector".equals(e.type())));
    }

    @Test
    void sanitizeHtml_scriptタグを除去する() {
        String sanitized = service.sanitizeHtml("<div>{{content}}<script>alert(1)</script></div>");

        assertFalse(sanitized.toLowerCase().contains("<script"));
        assertTrue(sanitized.contains("{{content}}"));
    }

    @Test
    void sanitizeHtml_イベントハンドラ属性を除去する() {
        String sanitized = service.sanitizeHtml("<div onclick=\"alert(1)\">{{content}}</div>");

        assertFalse(sanitized.contains("onclick"));
    }

    @Test
    void sanitizeHtml_javascriptプロトコルのhrefを無害化する() {
        String sanitized = service.sanitizeHtml("<a href=\"javascript:alert(1)\">link</a>");

        assertFalse(sanitized.contains("javascript:"));
    }

    @Test
    void sanitizeHtml_nullはそのまま空文字を返す() {
        assertEquals("", service.sanitizeHtml(null));
    }
}
