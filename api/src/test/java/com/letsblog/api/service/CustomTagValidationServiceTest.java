package com.letsblog.api.service;

import com.letsblog.api.dto.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CustomTagValidationServiceTest {

    private CustomTagValidationService validationService;

    @BeforeEach
    void setup() {
        validationService = new CustomTagValidationService();
    }

    @Test
    void testValidate_ValidHtmlAndCss() {
        String html = "<div class=\"alert\">{{content}}</div>";
        String css = ".alert { color: red; padding: 10px; }";

        ValidationResult result = validationService.validate(html, css);

        assertTrue(result.isValid());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void testValidate_DetectsScriptTag() {
        String html = "<div><script>alert('xss')</script>{{content}}</div>";
        String css = "";

        ValidationResult result = validationService.validate(html, css);

        assertTrue(result.errors().stream().anyMatch(e -> "script-tag-detected".equals(e.type())));
    }

    @Test
    void testValidate_DetectsEventHandlers() {
        String html = "<div onclick=\"alert('xss')\">{{content}}</div>";
        String css = "";

        ValidationResult result = validationService.validate(html, css);

        assertTrue(result.errors().stream().anyMatch(e -> "event-handler-detected".equals(e.type())));
    }

    @Test
    void testValidate_DetectsJavaScriptProtocol() {
        String html = "<a href=\"javascript:alert('xss')\">{{content}}</a>";
        String css = "";

        ValidationResult result = validationService.validate(html, css);

        assertTrue(result.errors().stream().anyMatch(e -> "javascript-protocol-detected".equals(e.type())));
    }

    @Test
    void testValidate_WarnsAboutMissingContentPlaceholder() {
        String html = "<div>Static content only</div>";
        String css = "";

        ValidationResult result = validationService.validate(html, css);

        assertTrue(result.warnings().stream().anyMatch(w -> "missing-content-placeholder".equals(w.type())));
    }

    @Test
    void testValidate_EmptyHtml() {
        String html = "";
        String css = "";

        ValidationResult result = validationService.validate(html, css);

        assertTrue(result.errors().stream().anyMatch(e -> "empty-html".equals(e.type())));
    }

    @Test
    void testValidate_NullHtml() {
        String html = null;
        String css = "";

        ValidationResult result = validationService.validate(html, css);

        assertTrue(result.errors().stream().anyMatch(e -> "empty-html".equals(e.type())));
    }

    @Test
    void testValidate_DetectsCssInjection() {
        String html = "<div>{{content}}</div>";
        String css = ".alert { behavior: url(xss.htc); }";

        ValidationResult result = validationService.validate(html, css);

        assertTrue(result.errors().stream().anyMatch(e -> "css-injection-detected".equals(e.type())));
    }

    @Test
    void testValidate_MultipleErrors() {
        String html = "<div onclick=\"alert()\"><script>evil()</script>{{content}}</div>";
        String css = "";

        ValidationResult result = validationService.validate(html, css);

        assertTrue(result.errors().size() >= 2);
        assertTrue(result.errors().stream().anyMatch(e -> "script-tag-detected".equals(e.type())));
        assertTrue(result.errors().stream().anyMatch(e -> "event-handler-detected".equals(e.type())));
    }

    @Test
    void testSanitizeHtml_RemovesScriptTags() {
        String html = "<div><script>alert('xss')</script>{{content}}</div>";

        String sanitized = validationService.sanitizeHtml(html);

        assertFalse(sanitized.contains("<script>"));
        assertTrue(sanitized.contains("{{content}}"));
    }

    @Test
    void testSanitizeHtml_RemovesEventHandlers() {
        String html = "<div onclick=\"alert('xss')\" class=\"alert\">{{content}}</div>";

        String sanitized = validationService.sanitizeHtml(html);

        assertFalse(sanitized.contains("onclick"));
        assertTrue(sanitized.contains("class"));
        assertTrue(sanitized.contains("{{content}}"));
    }

    @Test
    void testSanitizeHtml_RemovesJavaScriptProtocol() {
        String html = "<a href=\"javascript:alert('xss')\">{{content}}</a>";

        String sanitized = validationService.sanitizeHtml(html);

        assertFalse(sanitized.contains("javascript:"));
        assertTrue(sanitized.contains("href="));
    }

    @Test
    void testSanitizeHtml_NullInput() {
        String sanitized = validationService.sanitizeHtml(null);

        assertEquals("", sanitized);
    }

    @Test
    void testValidate_ValidComplexHtml() {
        String html = """
            <div class="card">
                <h2>{{attr:title}}</h2>
                <p>{{content}}</p>
                <button class="btn-primary">Click me</button>
            </div>
            """;
        String css = """
            .card {
                border: 1px solid #ddd;
                padding: 20px;
                border-radius: 8px;
            }
            .btn-primary {
                background-color: #007bff;
                color: white;
                padding: 10px 20px;
            }
            """;

        ValidationResult result = validationService.validate(html, css);

        assertTrue(result.errors().isEmpty());
    }
}
