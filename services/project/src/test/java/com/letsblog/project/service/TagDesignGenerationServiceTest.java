package com.letsblog.project.service;

import com.letsblog.project.client.AiGenerationClient;
import com.letsblog.project.domain.EmbedTagType;
import com.letsblog.project.dto.GenerateTagDesignResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TagDesignGenerationServiceの回帰テスト(issue #183)。issue #574でLLM呼び出し(プロジェクトの
 * 選択中モデル解決も含む)をai-serviceへ委譲するAiGenerationClient経由に変わったため、
 * CSS/HTMLの抽出、CSSが得られない場合のエラーを中心に検証する。
 */
@ExtendWith(MockitoExtension.class)
class TagDesignGenerationServiceTest {

    @Mock
    private AiGenerationClient aiGenerationClient;

    @InjectMocks
    private TagDesignGenerationService tagDesignGenerationService;

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
        when(aiGenerationClient.generate(eq(42L), anyString(), isNull())).thenReturn(response);

        GenerateTagDesignResponse result =
                tagDesignGenerationService.generate(42L, EmbedTagType.TOC, "背景を白にして", null);

        assertTrue(result.cssContent().contains("lb-toc-list"));
        assertTrue(result.htmlTemplate().contains("{{toc}}"));
        verify(aiGenerationClient).generate(eq(42L), anyString(), isNull());
    }

    @Test
    void generate_HTMLブロックが省略された場合は空文字を返す() {
        String response = """
                ```css
                .lb-blogcard{border-radius:12px;}
                ```
                """;
        when(aiGenerationClient.generate(eq(42L), anyString(), isNull())).thenReturn(response);

        GenerateTagDesignResponse result =
                tagDesignGenerationService.generate(42L, EmbedTagType.BLOGCARD, "角を丸く", "<div>{{title}}</div>");

        assertTrue(result.cssContent().contains("border-radius"));
        assertEquals("", result.htmlTemplate());
    }

    @Test
    void generate_CSSを抽出できなければ例外を投げる() {
        when(aiGenerationClient.generate(eq(42L), anyString(), isNull())).thenReturn("CSSブロックなしの応答");

        InvalidCustomTagContentException exception = assertThrows(
                InvalidCustomTagContentException.class,
                () -> tagDesignGenerationService.generate(42L, EmbedTagType.AMAZON, "テスト", null));

        assertTrue(exception.getMessage().contains("CSSを抽出できません"));
    }
}
