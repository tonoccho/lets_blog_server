package com.letsblog.content.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.stream.Collectors;

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

    /** ジョブ1件の完了行(issue #1731)だけを集める。 */
    private static List<ILoggingEvent> completionLines(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(CustomTagGenerationJobRunner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith("job completed:"))
                .collect(Collectors.toList());
    }

    @org.junit.jupiter.api.Test
    void 完了行_成功はINFOでkey_valueの1行を出す() throws Exception {
        when(generationService.generateContent(anyString()))
                .thenReturn(new CustomTagGenerationService.GeneratedContent("<b/>", ""));
        List<ILoggingEvent> lines = completionLines(() -> { runner.run(31L, REQUEST); });

        org.junit.jupiter.api.Assertions.assertEquals(1, lines.size(), lines.toString());
        org.junit.jupiter.api.Assertions.assertEquals(Level.INFO, lines.get(0).getLevel());
        org.junit.jupiter.api.Assertions.assertTrue(lines.get(0).getFormattedMessage()
                .matches("job completed: kind=custom_tag_generation job_id=31 duration_ms=\\d+ outcome=success"),
                lines.get(0).getFormattedMessage());
    }

    @org.junit.jupiter.api.Test
    void 完了行_失敗はWARN以上で例外クラス名をoutcomeに出す() throws Exception {
        when(generationService.generateContent(anyString()))
                .thenThrow(new InvalidCustomTagContentException("抽出できない"));
        List<ILoggingEvent> lines = completionLines(() -> { runner.run(31L, REQUEST); });

        org.junit.jupiter.api.Assertions.assertEquals(1, lines.size(), lines.toString());
        org.junit.jupiter.api.Assertions.assertTrue(lines.get(0).getLevel().isGreaterOrEqual(Level.WARN));
        org.junit.jupiter.api.Assertions.assertTrue(lines.get(0).getFormattedMessage()
                .matches("job completed: kind=custom_tag_generation job_id=31 duration_ms=\\d+ outcome=InvalidCustomTagContentException"),
                lines.get(0).getFormattedMessage());
    }
}
