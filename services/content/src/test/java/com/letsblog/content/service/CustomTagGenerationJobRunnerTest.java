package com.letsblog.content.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.content.client.AiServiceException;
import com.letsblog.content.dto.GenerateCustomTagRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * カスタムタグ生成ジョブの実行本体(issue #1409)。{@code @Async}はSpringプロキシ経由でしか効かないため、
 * メソッドを同期的に呼んで検証する。生成結果はジョブの結果へだけ載せ、保存先へは書かない
 * (ランナーは保存先のリポジトリを持たない)ことを、結果の形で固定する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("content-service: カスタムタグ生成ジョブランナー(issue #1409)")
class CustomTagGenerationJobRunnerTest {

    private static final GenerateCustomTagRequest REQUEST =
            new GenerateCustomTagRequest("青いボタン", "blue-button", "説明", 4L);

    @Mock
    private CustomTagGenerationService generationService;
    @Mock
    private GenerationJobClient generationJobClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CustomTagGenerationJobRunner runner;

    @BeforeEach
    void setUp() {
        runner = new CustomTagGenerationJobRunner(generationService, generationJobClient, objectMapper);
    }

    private JsonNode terminalPayload(String status) throws Exception {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).updateStatus(org.mockito.ArgumentMatchers.eq(31L),
                org.mockito.ArgumentMatchers.eq(status), payload.capture());
        return objectMapper.readTree(payload.getValue());
    }

    @Test
    @DisplayName("成功すると、生成したHTML/CSSとタグ名などを done の結果へ載せる")
    void success() throws Exception {
        when(generationService.generateContent("青いボタン"))
                .thenReturn(new CustomTagGenerationService.GeneratedContent("<b>{{content}}</b>", ".b{}"));

        runner.run(31L, REQUEST);

        JsonNode result = terminalPayload("done");
        assertEquals("<b>{{content}}</b>", result.get("htmlTemplate").asText());
        assertEquals(".b{}", result.get("cssContent").asText());
        assertEquals("blue-button", result.get("tagName").asText());
        assertEquals("説明", result.get("description").asText());
        assertEquals(4L, result.get("projectId").asLong());
        // 保存先への書き込みは、生成サービスの生成だけを呼ぶ形でしか起こりえない。
        verify(generationService).generateContent("青いボタン");
        verifyNoMoreInteractions(generationService);
    }

    @Test
    @DisplayName("説明とプロジェクトが無いときは null で載せる(グローバルタグ)")
    void successWithoutOptionalFields() throws Exception {
        when(generationService.generateContent(anyString()))
                .thenReturn(new CustomTagGenerationService.GeneratedContent("<b/>", ""));

        runner.run(31L, new GenerateCustomTagRequest("p", "t", null, null));

        JsonNode result = terminalPayload("done");
        assertTrue(result.get("description").isNull());
        assertTrue(result.get("projectId").isNull());
    }

    @Test
    @DisplayName("HTMLを抽出できない・検証に落ちたときは invalid_content の failed")
    void invalidContent() throws Exception {
        when(generationService.generateContent(anyString()))
                .thenThrow(new InvalidCustomTagContentException("LLMレスポンスからHTMLを抽出できませんでした"));

        runner.run(31L, REQUEST);

        JsonNode result = terminalPayload("failed");
        assertEquals("invalid_content", result.get("errorType").asText());
        assertTrue(result.get("error").asText().contains("HTMLを抽出できませんでした"));
    }

    @Test
    @DisplayName("ai-service に届かないときは ai_unavailable の failed")
    void aiUnavailable() throws Exception {
        when(generationService.generateContent(anyString())).thenThrow(new AiServiceException("down", null));

        runner.run(31L, REQUEST);

        assertEquals("ai_unavailable", terminalPayload("failed").get("errorType").asText());
    }

    @Test
    @DisplayName("想定外の例外は generation_failed の failed で、メッセージが無くても理由が空にならない")
    void unexpected() throws Exception {
        when(generationService.generateContent(anyString())).thenThrow(new IllegalStateException());

        runner.run(31L, REQUEST);

        JsonNode result = terminalPayload("failed");
        assertEquals("generation_failed", result.get("errorType").asText());
        assertFalse(result.get("error").asText().isBlank());
    }
}
