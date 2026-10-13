package com.letsblog.media.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.media.ai.AiServiceException;
import com.letsblog.media.ai.ComfyUiUnreachableException;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.media.dto.AiImageRequest;
import com.letsblog.media.dto.AiImageResponse;
import com.letsblog.media.service.ImageGenerationService.BatchOutcome;
import com.letsblog.media.service.ImageGenerationService.RepeatProgressListener;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 画像生成ジョブの実行本体(issue #1405)。ModelInstallJobRunnerTestと同様、{@code @Async}は
 * Springプロキシ経由でしか効かないため、メソッドを同期的に呼んで検証する。
 *
 * <p>結果はBase64ではなく生成画像のIDで{@code result_payload}へ残す(#1112のオンヒープ保持を
 * 非同期経路で再現しないため)。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("media-service: 画像生成ジョブランナー(issue #1405)")
class ImageGenerationJobRunnerTest {

    private static final AiImageRequest REQUEST = AiImageRequest.withDefaults("a cat");

    @Mock
    private ImageGenerationService imageGenerationService;
    @Mock
    private GenerationJobClient generationJobClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ImageGenerationJobRunner runner;
    private ImageGenerationJobTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ImageGenerationJobTracker(generationJobClient, objectMapper);
        runner = new ImageGenerationJobRunner(imageGenerationService, generationJobClient, objectMapper, tracker);
    }

    private static AiImageResponse image(long id) {
        return new AiImageResponse(id, "f.png", null, "image/png", null, 0);
    }

    private JsonNode payloadOf(String status) throws Exception {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).updateStatus(eq(7L), eq(status), payload.capture());
        return objectMapper.readTree(payload.getValue());
    }

    @Test
    void 成功すると生成画像のIDをdoneのresult_payloadに残しBase64は含めない() throws Exception {
        when(imageGenerationService.generateBatch(eq(REQUEST), eq(false), any()))
                .thenReturn(new BatchOutcome(List.of(image(11), image(12)), 1, 0, 1, false));

        runner.run(7L, REQUEST);

        JsonNode result = payloadOf("done");
        assertEquals(List.of(11L, 12L), List.of(result.get("imageIds").get(0).asLong(), result.get("imageIds").get(1).asLong()));
        assertEquals(2, result.get("count").asInt());
        assertEquals(1, result.get("succeededRepeats").asInt());
        assertEquals(0, result.get("failedRepeats").asInt());
        assertFalse(result.toString().contains("dataBase64"));
    }

    @Test
    void 一部のリピートが失敗しても成功分のIDと失敗数をdoneで残す() throws Exception {
        when(imageGenerationService.generateBatch(eq(REQUEST), eq(false), any()))
                .thenReturn(new BatchOutcome(List.of(image(1)), 1, 2, 2, true));

        runner.run(7L, REQUEST);

        JsonNode result = payloadOf("done");
        assertEquals(2, result.get("failedRepeats").asInt());
        assertEquals(2, result.get("attemptedRepeats").asInt());
        assertTrue(result.get("aborted").asBoolean());
    }

    @Test
    void リピートが終わるたびに進捗をrunningで書く() throws Exception {
        when(imageGenerationService.generateBatch(eq(REQUEST), eq(false), any())).thenAnswer(inv -> {
            RepeatProgressListener listener = inv.getArgument(2);
            listener.repeatFinished(1, 4);
            listener.repeatFinished(2, 4);
            return new BatchOutcome(List.of(image(1)), 2, 0, 2, false);
        });

        runner.run(7L, REQUEST);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient, times(3)).updateStatus(eq(7L), eq("running"), payload.capture());
        JsonNode start = objectMapper.readTree(payload.getAllValues().get(0));
        JsonNode second = objectMapper.readTree(payload.getAllValues().get(2));
        assertEquals("generating", start.get("phase").asText());
        assertEquals(0, start.get("percent").asInt());
        assertEquals(50, second.get("percent").asInt());
    }

    @Test
    void 禁止コンテンツはfailedで種別prohibited_contentと理由を残す() throws Exception {
        when(imageGenerationService.generateBatch(any(), eq(false), any()))
                .thenThrow(new ProhibitedContentException("禁止語を含みます"));

        runner.run(7L, REQUEST);

        JsonNode result = payloadOf("failed");
        assertEquals("prohibited_content", result.get("errorType").asText());
        assertEquals("禁止語を含みます", result.get("error").asText());
        verify(generationJobClient, never()).updateStatus(eq(7L), eq("done"), any());
    }

    @Test
    void ComfyUI不到達はfailedで種別provider_unreachableと接続先を残す() throws Exception {
        when(imageGenerationService.generateBatch(any(), eq(false), any())).thenThrow(new ComfyUiUnreachableException(
                "ComfyUIへ到達できません(接続先: http://comfy:8188): refused",
                new ResourceAccessException("refused")));

        runner.run(7L, REQUEST);

        JsonNode result = payloadOf("failed");
        assertEquals("provider_unreachable", result.get("errorType").asText());
        assertTrue(result.get("error").asText().contains("http://comfy:8188"));
    }

    @Test
    void プロバイダの429はfailedで種別rate_limitedを残す() throws Exception {
        when(imageGenerationService.generateBatch(any(), eq(false), any())).thenThrow(new AiServiceException(
                "ChatGPT画像生成の呼び出しに失敗しました: 429 too many",
                HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many", null, null, null)));

        runner.run(7L, REQUEST);

        assertEquals("rate_limited", payloadOf("failed").get("errorType").asText());
    }

    @Test
    void 到達不能以外のプロバイダ障害はgeneration_failedになる() throws Exception {
        when(imageGenerationService.generateBatch(any(), eq(false), any())).thenThrow(new AiServiceException(
                "ComfyUIの画像生成がタイムアウトしました", null));

        runner.run(7L, REQUEST);

        assertEquals("generation_failed", payloadOf("failed").get("errorType").asText());
    }

    @Test
    void 原因がHTTP応答でも429以外ならgeneration_failedになる() throws Exception {
        when(imageGenerationService.generateBatch(any(), eq(false), any())).thenThrow(new AiServiceException(
                "ChatGPT画像生成の呼び出しに失敗しました: 500",
                HttpClientErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "x", null, null, null)));

        runner.run(7L, REQUEST);

        assertEquals("generation_failed", payloadOf("failed").get("errorType").asText());
    }

    @Test
    void 原因がResourceAccessExceptionでもComfyUI不到達の型でなければgeneration_failed() throws Exception {
        when(imageGenerationService.generateBatch(any(), eq(false), any())).thenThrow(new AiServiceException(
                "ChatGPT画像生成中にエラーが発生しました", new ResourceAccessException("timeout")));

        runner.run(7L, REQUEST);

        assertEquals("generation_failed", payloadOf("failed").get("errorType").asText());
    }

    @Test
    void 想定外の例外もfailedにしてジョブをrunningのまま残さない() throws Exception {
        when(imageGenerationService.generateBatch(any(), eq(false), any()))
                .thenThrow(new IllegalStateException("boom"));

        runner.run(7L, REQUEST);

        JsonNode result = payloadOf("failed");
        assertEquals("generation_failed", result.get("errorType").asText());
        assertEquals("boom", result.get("error").asText());
    }

    @Test
    void 例外のメッセージがnullでも失敗を記録できる() throws Exception {
        when(imageGenerationService.generateBatch(any(), eq(false), any()))
                .thenThrow(new IllegalStateException((String) null));

        runner.run(7L, REQUEST);

        assertEquals("generation_failed", payloadOf("failed").get("errorType").asText());
    }

    @Test
    void 進捗の総数が0でも0除算せずpercentは0になる() throws Exception {
        when(imageGenerationService.generateBatch(eq(REQUEST), eq(false), any())).thenAnswer(inv -> {
            RepeatProgressListener listener = inv.getArgument(2);
            listener.repeatFinished(0, 0);
            return new BatchOutcome(List.of(), 0, 0, 0, false);
        });

        runner.run(7L, REQUEST);

        verify(generationJobClient).updateStatus(eq(7L), eq("done"), any());
    }

    @Test
    void 完了後はハートビートの追跡から外れる() {
        tracker.track(7L);
        when(imageGenerationService.generateBatch(any(), eq(false), any()))
                .thenReturn(new BatchOutcome(List.of(image(1)), 1, 0, 1, false));

        runner.run(7L, REQUEST);

        assertFalse(tracker.isTracked(7L));
    }

    @Test
    void 失敗後もハートビートの追跡から外れる() {
        tracker.track(7L);
        when(imageGenerationService.generateBatch(any(), eq(false), any()))
                .thenThrow(new IllegalStateException("boom"));

        runner.run(7L, REQUEST);

        assertFalse(tracker.isTracked(7L));
    }

    @Test
    void 進捗はトラッカーにも反映されハートビートが最新値を使う() throws Exception {
        tracker.track(7L);
        when(imageGenerationService.generateBatch(eq(REQUEST), eq(false), any())).thenAnswer(inv -> {
            RepeatProgressListener listener = inv.getArgument(2);
            listener.repeatFinished(1, 4);
            tracker.heartbeat();
            return new BatchOutcome(List.of(image(1)), 1, 0, 1, false);
        });

        runner.run(7L, REQUEST);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        // 開始(0%)・リピート完了(25%)・ハートビートの3回。ハートビートは直近の25%を使う。
        verify(generationJobClient, times(3)).updateStatus(eq(7L), eq("running"), payload.capture());
        assertEquals(25, objectMapper.readTree(payload.getAllValues().get(2)).get("percent").asInt());
    }

    /** ジョブ1件の完了行(issue #1731)だけを集める。 */
    private static List<ILoggingEvent> completionLines(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(ImageGenerationJobRunner.class);
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
        when(imageGenerationService.generateBatch(eq(REQUEST), eq(false), any()))
                .thenReturn(new BatchOutcome(List.of(image(11)), 1, 0, 1, false));
        List<ILoggingEvent> lines = completionLines(() -> { runner.run(7L, REQUEST); });

        org.junit.jupiter.api.Assertions.assertEquals(1, lines.size(), lines.toString());
        org.junit.jupiter.api.Assertions.assertEquals(Level.INFO, lines.get(0).getLevel());
        org.junit.jupiter.api.Assertions.assertTrue(lines.get(0).getFormattedMessage()
                .matches("job completed: kind=image_generation job_id=7 duration_ms=\\d+ outcome=success"),
                lines.get(0).getFormattedMessage());
    }

    @org.junit.jupiter.api.Test
    void 完了行_失敗はWARN以上で例外クラス名をoutcomeに出す() throws Exception {
        when(imageGenerationService.generateBatch(any(), eq(false), any()))
                .thenThrow(new IllegalStateException("boom"));
        List<ILoggingEvent> lines = completionLines(() -> { runner.run(7L, REQUEST); });

        org.junit.jupiter.api.Assertions.assertEquals(1, lines.size(), lines.toString());
        org.junit.jupiter.api.Assertions.assertTrue(lines.get(0).getLevel().isGreaterOrEqual(Level.WARN));
        org.junit.jupiter.api.Assertions.assertTrue(lines.get(0).getFormattedMessage()
                .matches("job completed: kind=image_generation job_id=7 duration_ms=\\d+ outcome=IllegalStateException"),
                lines.get(0).getFormattedMessage());
    }
}
