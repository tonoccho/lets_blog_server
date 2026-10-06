package com.letsblog.project.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.project.client.AiServiceException;
import com.letsblog.project.client.BearerScope;
import com.letsblog.project.domain.EmbedTagType;
import com.letsblog.project.domain.StaticContentType;
import com.letsblog.project.dto.GenerateTagDesignResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 静的コンテンツ・タグデザイン生成ジョブの実行本体(issue #1409)。{@code @Async}はSpringプロキシ経由
 * でしか効かないため、メソッドを同期的に呼んで検証する。生成結果はジョブの結果へだけ載せ、
 * 保存先へは書かない(保存はユーザーの「保存」操作で既存の保存APIが行う)。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("project-service: テキスト生成ジョブランナー(issue #1409)")
class TextGenerationJobRunnerTest {

    @Mock
    private StaticContentGenerationService staticContentGenerationService;
    @Mock
    private TagDesignGenerationService tagDesignGenerationService;
    @Mock
    private TagDesignSettingService tagDesignSettingService;
    @Mock
    private GenerationJobClient generationJobClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TextGenerationJobRunner runner;

    @BeforeEach
    void setUp() {
        runner = new TextGenerationJobRunner(
                staticContentGenerationService, tagDesignGenerationService, tagDesignSettingService,
                generationJobClient, objectMapper);
    }

    private JsonNode terminalPayload(long jobId, String status) throws Exception {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).updateStatus(
                org.mockito.ArgumentMatchers.eq(jobId), org.mockito.ArgumentMatchers.eq(status), payload.capture());
        return objectMapper.readTree(payload.getValue());
    }

    @Test
    @DisplayName("静的コンテンツ: 生成した本文を done の結果へ載せる。取り置いたBearerの範囲で生成し、終われば外れる")
    void staticContentSuccess() throws Exception {
        when(staticContentGenerationService.generateBody(5L, StaticContentType.PRIVACY_POLICY)).thenAnswer(inv -> {
            assertEquals("Bearer abc", BearerScope.current());
            return "生成された本文";
        });

        runner.runStaticContent(11L, 5L, StaticContentType.PRIVACY_POLICY, "Bearer abc");

        JsonNode result = terminalPayload(11L, "done");
        assertEquals(5L, result.get("siteId").asLong());
        assertEquals("PRIVACY_POLICY", result.get("contentType").asText());
        assertEquals("生成された本文", result.get("body").asText());
        assertNull(BearerScope.current());
        // 保存先(static_content)を書くのは generate / save だけ。ランナーは generateBody しか呼ばない。
        verify(staticContentGenerationService).generateBody(5L, StaticContentType.PRIVACY_POLICY);
        verifyNoMoreInteractions(staticContentGenerationService);
    }

    @Test
    @DisplayName("静的コンテンツ: サイトが無いなら site_not_found の failed")
    void staticContentSiteNotFound() throws Exception {
        when(staticContentGenerationService.generateBody(any(), any())).thenThrow(new SiteNotFoundException("無い"));

        runner.runStaticContent(11L, 5L, StaticContentType.PRIVACY_POLICY, "Bearer abc");

        assertEquals("site_not_found", terminalPayload(11L, "failed").get("errorType").asText());
    }

    @Test
    @DisplayName("静的コンテンツ: LLM応答が空・プラグイン情報が取れないなら generation_failed の failed")
    void staticContentGenerationFailed() throws Exception {
        when(staticContentGenerationService.generateBody(any(), any()))
                .thenThrow(new AiServiceGenerationException("LLMレスポンスが空でした"));

        runner.runStaticContent(11L, 5L, StaticContentType.OPERATOR_INFO, "Bearer abc");

        JsonNode result = terminalPayload(11L, "failed");
        assertEquals("generation_failed", result.get("errorType").asText());
        assertEquals("LLMレスポンスが空でした", result.get("error").asText());
    }

    @Test
    @DisplayName("ai-service に届かないなら ai_unavailable の failed")
    void aiUnavailable() throws Exception {
        when(staticContentGenerationService.generateBody(any(), any())).thenThrow(new AiServiceException("down", null));

        runner.runStaticContent(11L, 5L, StaticContentType.TERMS_OF_SERVICE, null);

        assertEquals("ai_unavailable", terminalPayload(11L, "failed").get("errorType").asText());
    }

    @Test
    @DisplayName("想定外の例外は unexpected_error の failed で、メッセージが無くても理由が空にならない")
    void unexpected() throws Exception {
        when(staticContentGenerationService.generateBody(any(), any())).thenThrow(new IllegalStateException());

        runner.runStaticContent(11L, 5L, StaticContentType.PRIVACY_POLICY, "Bearer abc");

        JsonNode result = terminalPayload(11L, "failed");
        assertEquals("unexpected_error", result.get("errorType").asText());
        assertFalse(result.get("error").asText().isBlank());
    }

    @Test
    @DisplayName("タグデザイン: 現在のHTMLを渡して生成し、CSS/HTMLを done の結果へ載せる(保存はしない)")
    void tagDesignSuccess() throws Exception {
        when(tagDesignSettingService.resolveHtmlTemplate(3L, EmbedTagType.TOC)).thenReturn("{{toc}}");
        when(tagDesignGenerationService.generate(3L, EmbedTagType.TOC, "淡いグレー", "{{toc}}"))
                .thenReturn(new GenerateTagDesignResponse("<nav>{{toc}}</nav>", ".lb-toc-list{}"));

        runner.runTagDesign(12L, 3L, EmbedTagType.TOC, "淡いグレー");

        JsonNode result = terminalPayload(12L, "done");
        assertEquals(3L, result.get("projectId").asLong());
        assertEquals("TOC", result.get("tagType").asText());
        assertEquals("<nav>{{toc}}</nav>", result.get("htmlTemplate").asText());
        assertEquals(".lb-toc-list{}", result.get("cssContent").asText());
        verify(tagDesignSettingService).resolveHtmlTemplate(3L, EmbedTagType.TOC);
        verifyNoMoreInteractions(tagDesignSettingService);
    }

    @Test
    @DisplayName("タグデザイン(グローバル): projectId null のまま現在のHTMLを引き、結果の projectId は null")
    void tagDesignGlobal() throws Exception {
        when(tagDesignSettingService.resolveHtmlTemplate(null, EmbedTagType.AMAZON)).thenReturn(null);
        when(tagDesignGenerationService.generate(null, EmbedTagType.AMAZON, "p", null))
                .thenReturn(new GenerateTagDesignResponse("", ".lb-amazon-card{}"));

        runner.runTagDesign(12L, null, EmbedTagType.AMAZON, "p");

        JsonNode result = terminalPayload(12L, "done");
        assertEquals(true, result.get("projectId").isNull());
        assertEquals("", result.get("htmlTemplate").asText());
    }

    @Test
    @DisplayName("タグデザイン: CSSを抽出できないなら invalid_content の failed")
    void tagDesignInvalidContent() throws Exception {
        when(tagDesignGenerationService.generate(any(), any(), any(), any()))
                .thenThrow(new InvalidCustomTagContentException("CSSを抽出できませんでした"));

        runner.runTagDesign(12L, 3L, EmbedTagType.TOC, "p");

        assertEquals("invalid_content", terminalPayload(12L, "failed").get("errorType").asText());
    }
}
