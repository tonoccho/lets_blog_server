package com.letsblog.api.service;

import com.letsblog.api.ai.LlmClient;
import com.letsblog.api.domain.EmbedTagType;
import com.letsblog.api.dto.GenerateTagDesignResponse;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TagDesignGenerationServiceの回帰テスト(issue #183)。プロジェクトの選択中モデルを使うこと、
 * CSS/HTMLの抽出、CSSが得られない場合のエラーを検証する。
 */
@ExtendWith(MockitoExtension.class)
class TagDesignGenerationServiceTest {

    @Mock
    private LlmClient llmClient;

    @Mock
    private LlmModelService llmModelService;

    @InjectMocks
    private TagDesignGenerationService tagDesignGenerationService;

    @Test
    void generate_プロジェクトの選択中モデルでCSSとHTMLを抽出する() {
        when(llmModelService.getSelectedModel(42L)).thenReturn("qwen2.5:14b");
        String response = """
                ```css
                .lb-toc-list{background:#fff;}
                ```

                ```html
                {{toc}}
                ```
                """;
        when(llmClient.generate(anyString(), eq("qwen2.5:14b"))).thenReturn(response);

        GenerateTagDesignResponse result =
                tagDesignGenerationService.generate(42L, EmbedTagType.TOC, "背景を白にして", null);

        assertTrue(result.cssContent().contains("lb-toc-list"));
        assertTrue(result.htmlTemplate().contains("{{toc}}"));
        verify(llmClient).generate(anyString(), eq("qwen2.5:14b"));
    }

    @Test
    void generate_HTMLブロックが省略された場合は空文字を返す() {
        when(llmModelService.getSelectedModel(42L)).thenReturn("qwen2.5:14b");
        String response = """
                ```css
                .lb-blogcard{border-radius:12px;}
                ```
                """;
        when(llmClient.generate(anyString(), eq("qwen2.5:14b"))).thenReturn(response);

        GenerateTagDesignResponse result =
                tagDesignGenerationService.generate(42L, EmbedTagType.BLOGCARD, "角を丸く", "<div>{{title}}</div>");

        assertTrue(result.cssContent().contains("border-radius"));
        assertEquals("", result.htmlTemplate());
    }

    @Test
    void generate_CSSを抽出できなければ例外を投げる() {
        when(llmModelService.getSelectedModel(42L)).thenReturn("qwen2.5:14b");
        when(llmClient.generate(anyString(), eq("qwen2.5:14b"))).thenReturn("CSSブロックなしの応答");

        InvalidCustomTagContentException exception = assertThrows(
                InvalidCustomTagContentException.class,
                () -> tagDesignGenerationService.generate(42L, EmbedTagType.AMAZON, "テスト", null));

        assertTrue(exception.getMessage().contains("CSSを抽出できません"));
    }
}
