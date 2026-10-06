package com.letsblog.content.service;

import com.letsblog.content.client.AiGenerationClient;
import com.letsblog.content.domain.CustomTag;
import com.letsblog.content.dto.GenerateCustomTagRequest;
import com.letsblog.content.dto.GenerateCustomTagResponse;
import com.letsblog.content.dto.ValidationError;
import com.letsblog.content.dto.ValidationResult;
import com.letsblog.content.render.MediaRenderClient;
import com.letsblog.content.repository.CustomTagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * issue #656: #641のレビューで、CustomTag*サービス群のうちCustomTagGenerationServiceが未テストの
 * まま残っていたことを受けて追加。LLMレスポンスからのHTML/CSS抽出・バリデーション連携・重複チェック・
 * Penpotデザインファイル作成のベストエフォートフォールバックを検証する。
 */
@ExtendWith(MockitoExtension.class)
class CustomTagGenerationServiceTest {

    @Mock
    private AiGenerationClient aiGenerationClient;
    @Mock
    private MediaRenderClient mediaRenderClient;
    @Mock
    private CustomTagRepository customTagRepository;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;
    @Mock
    private CustomTagValidationService customTagValidationService;

    private CustomTagGenerationService service;

    @BeforeEach
    void setUp() {
        service = new CustomTagGenerationService(
                aiGenerationClient, mediaRenderClient, customTagRepository,
                adminAuthorizationService, customTagValidationService);
    }

    private GenerateCustomTagRequest buildRequest() {
        return new GenerateCustomTagRequest("シンプルな注意書きカードを作って", "note", "desc", null);
    }

    private String llmResponse(String html, String css) {
        return "```html\n" + html + "\n```\n\n```css\n" + css + "\n```";
    }

    // ---- issue #1409: 保存せずに生成内容だけを返す(非同期ジョブ用) ----

    @Test
    void generateContent_HTMLとCSSを返し保存も管理者確認もしない() {
        String html = "<div class=\"note\">{{content}}</div>";
        String css = ".note { color: red; }";
        when(aiGenerationClient.generate(any(), anyString(), any())).thenReturn(llmResponse(html, css));
        when(customTagValidationService.validate(html, css)).thenReturn(new ValidationResult(true, List.of(), List.of()));

        CustomTagGenerationService.GeneratedContent content = service.generateContent("注意書きカード");

        assertEquals(html, content.htmlTemplate());
        assertEquals(css, content.cssContent());
        verify(customTagRepository, never()).save(any());
        verify(mediaRenderClient, never()).createPenpotDesignFile(anyString(), anyString());
        verify(adminAuthorizationService, never()).requireAdmin();
    }

    @Test
    void generateContent_CSSが無ければ空文字で返す() {
        String html = "<p>{{content}}</p>";
        when(aiGenerationClient.generate(any(), anyString(), any())).thenReturn("```html\n" + html + "\n```");
        when(customTagValidationService.validate(html, "")).thenReturn(new ValidationResult(true, List.of(), List.of()));

        assertEquals("", service.generateContent("p").cssContent());
    }

    @Test
    void generateContent_HTMLを抽出できなければ例外() {
        when(aiGenerationClient.generate(any(), anyString(), any())).thenReturn("説明だけ");

        assertThrows(InvalidCustomTagContentException.class, () -> service.generateContent("p"));
    }

    @Test
    void generateContent_検証に落ちたら理由つきの例外() {
        String html = "<script>x</script>";
        when(aiGenerationClient.generate(any(), anyString(), any())).thenReturn("```html\n" + html + "\n```");
        when(customTagValidationService.validate(html, "")).thenReturn(new ValidationResult(
                false, List.of(ValidationError.of("script-tag-detected", "scriptは使えません", "error")), List.of()));

        InvalidCustomTagContentException e =
                assertThrows(InvalidCustomTagContentException.class, () -> service.generateContent("p"));
        assertTrue(e.getMessage().contains("scriptは使えません"));
    }

    @Test
    void generate_LLMレスポンスからHTMLとCSSを抽出しバリデーションを通れば保存する() {
        String html = "<div class=\"note\">{{content}}</div>";
        String css = ".note { color: red; }";
        when(aiGenerationClient.generate(any(), anyString(), any())).thenReturn(llmResponse(html, css));
        when(customTagValidationService.validate(html, css)).thenReturn(new ValidationResult(true, List.of(), List.of()));
        when(customTagRepository.findByTagNameAndProjectIdIsNull("note")).thenReturn(Optional.empty());
        when(customTagRepository.save(any(CustomTag.class))).thenAnswer(invocation -> {
            CustomTag saved = invocation.getArgument(0);
            saved.setId(1L);
            return saved;
        });
        when(mediaRenderClient.createPenpotDesignFile(anyString(), anyString()))
                .thenReturn(new MediaRenderClient.DesignFile("file-1", "proj-1", "https://penpot.example/file-1"));

        GenerateCustomTagResponse response = service.generate(buildRequest());

        assertEquals(1L, response.id());
        assertEquals("note", response.tagName());
        assertEquals(html, response.htmlTemplate());
        assertEquals(css, response.cssContent());
        assertEquals("https://penpot.example/file-1", response.penpotFileUrl());
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void generate_Penpotデザインファイル作成に失敗してもタグ生成は継続する() {
        String html = "<div class=\"note\">{{content}}</div>";
        String css = ".note { color: red; }";
        when(aiGenerationClient.generate(any(), anyString(), any())).thenReturn(llmResponse(html, css));
        when(customTagValidationService.validate(html, css)).thenReturn(new ValidationResult(true, List.of(), List.of()));
        when(customTagRepository.findByTagNameAndProjectIdIsNull("note")).thenReturn(Optional.empty());
        when(customTagRepository.save(any(CustomTag.class))).thenAnswer(invocation -> {
            CustomTag saved = invocation.getArgument(0);
            saved.setId(1L);
            return saved;
        });
        when(mediaRenderClient.createPenpotDesignFile(anyString(), anyString()))
                .thenThrow(new RuntimeException("penpot down"));

        GenerateCustomTagResponse response = service.generate(buildRequest());

        assertEquals(1L, response.id());
        assertNull(response.penpotFileUrl());
    }

    @Test
    void generate_HTMLが抽出できない場合はInvalidCustomTagContentException() {
        when(aiGenerationClient.generate(any(), anyString(), any())).thenReturn("説明のみでコードブロックなし");

        assertThrows(InvalidCustomTagContentException.class, () -> service.generate(buildRequest()));
        verify(customTagRepository, never()).save(any());
    }

    @Test
    void generate_バリデーション失敗時はInvalidCustomTagContentExceptionでエラー内容を含む() {
        String html = "<div>{{content}}<script>alert(1)</script></div>";
        when(aiGenerationClient.generate(any(), anyString(), any())).thenReturn(llmResponse(html, ""));
        when(customTagValidationService.validate(eq(html), any())).thenReturn(new ValidationResult(
                false, List.of(ValidationError.of("script-tag-detected", "scriptタグは許可されていません", "error")), List.of()));

        InvalidCustomTagContentException exception =
                assertThrows(InvalidCustomTagContentException.class, () -> service.generate(buildRequest()));

        assertTrue(exception.getMessage().contains("scriptタグは許可されていません"));
        verify(customTagRepository, never()).save(any());
    }

    @Test
    void generate_タグ名が既に存在する場合はIllegalArgumentExceptionで保存されない() {
        String html = "<div class=\"note\">{{content}}</div>";
        String css = ".note { color: red; }";
        when(aiGenerationClient.generate(any(), anyString(), any())).thenReturn(llmResponse(html, css));
        when(customTagValidationService.validate(html, css)).thenReturn(new ValidationResult(true, List.of(), List.of()));
        when(customTagRepository.findByTagNameAndProjectIdIsNull("note"))
                .thenReturn(Optional.of(new CustomTag()));

        assertThrows(IllegalArgumentException.class, () -> service.generate(buildRequest()));
        verify(customTagRepository, never()).save(any());
    }

    @Test
    void generate_admin権限が無ければForbiddenExceptionが伝播しLLM呼び出しは行われない() {
        doThrow(new ForbiddenException("no admin")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> service.generate(buildRequest()));
        verify(aiGenerationClient, never()).generate(any(), anyString(), any());
    }
}
