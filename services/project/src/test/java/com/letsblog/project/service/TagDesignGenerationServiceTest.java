package com.letsblog.project.service;

import com.letsblog.project.client.AiServiceException;
import com.letsblog.project.testsupport.ProjectWorld;
import com.letsblog.project.domain.EmbedTagType;
import com.letsblog.project.dto.GenerateTagDesignResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TagDesignGenerationServiceの回帰テスト(issue #183)。issue #574でLLM呼び出し(プロジェクトの
 * 選択中モデル解決も含む)をai-serviceへ委譲するAiGenerationClient経由に変わったため、
 * CSS/HTMLの抽出、CSSが得られない場合のエラーを中心に検証する。
 */
class TagDesignGenerationServiceTest {

    private static final String GENERATE = "/api/internal/ai/generate";

    private final ProjectWorld world = new ProjectWorld();
    private final TagDesignGenerationService tagDesignGenerationService =
            new TagDesignGenerationService(world.aiGenerationClient);

    @AfterEach
    void close() {
        world.close();
    }

    @Test
    void generate_プロジェクトIdを添えてai_serviceへ委譲しCSSとHTMLを抽出する() {
        String response = """
                ```css
                .lb-toc-list{background:#fff;}
                ```

                ```html
                {{toc}}
                ```
                """;
        world.aiReplies(response);

        GenerateTagDesignResponse result =
                tagDesignGenerationService.generate(42L, EmbedTagType.TOC, "背景を白にして", null);

        assertTrue(result.cssContent().contains("lb-toc-list"));
        assertTrue(result.htmlTemplate().contains("{{toc}}"));
        var sent = world.bodiesOf("POST", GENERATE);
        assertEquals(1, sent.size());
        assertEquals(42L, sent.get(0).get("projectId").asLong());
        assertTrue(sent.get(0).get("prompt").asText().contains("背景を白にして"));
        assertTrue(sent.get(0).get("providerOverride").isNull());
    }

    @Test
    void generate_ai_serviceの呼び出しが失敗すればAiServiceExceptionがそのまま伝わる() {
        world.fail("POST", GENERATE, 500);

        assertThrows(AiServiceException.class,
                () -> tagDesignGenerationService.generate(42L, EmbedTagType.TOC, "背景", null));
    }

    @Test
    void generate_現在のHTMLテンプレートが空白ならプロンプトは未設定として扱う() {
        world.aiReplies("```css\n.lb-toc-list{}\n```");

        tagDesignGenerationService.generate(42L, EmbedTagType.TOC, "背景", "  ");

        assertTrue(world.bodiesOf("POST", GENERATE).get(0).get("prompt").asText().contains("(未設定。標準テンプレートを使用中です)"));
    }

    @Test
    void generate_現在のHTMLテンプレートがあればプロンプトに載せる() {
        world.aiReplies("```css\n.lb-toc-list{}\n```");

        tagDesignGenerationService.generate(42L, EmbedTagType.TOC, "背景", "<nav>{{toc}}</nav>");

        String prompt = world.bodiesOf("POST", GENERATE).get(0).get("prompt").asText();
        assertTrue(prompt.contains("<nav>{{toc}}</nav>"));
        assertFalse(prompt.contains("(未設定。標準テンプレートを使用中です)"));
    }

    @Test
    void generate_HTMLブロックが省略された場合は空文字を返す() {
        String response = """
                ```css
                .lb-blogcard{border-radius:12px;}
                ```
                """;
        world.aiReplies(response);

        GenerateTagDesignResponse result =
                tagDesignGenerationService.generate(42L, EmbedTagType.BLOGCARD, "角を丸く", "<div>{{title}}</div>");

        assertTrue(result.cssContent().contains("border-radius"));
        assertEquals("", result.htmlTemplate());
    }

    @Test
    void generate_CSSを抽出できなければ例外を投げる() {
        world.aiReplies("CSSブロックなしの応答");

        InvalidCustomTagContentException exception = assertThrows(
                InvalidCustomTagContentException.class,
                () -> tagDesignGenerationService.generate(42L, EmbedTagType.AMAZON, "テスト", null));

        assertTrue(exception.getMessage().contains("CSSを抽出できません"));
    }
}
