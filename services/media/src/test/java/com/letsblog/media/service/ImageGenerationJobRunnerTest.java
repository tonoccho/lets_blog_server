package com.letsblog.media.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.media.dto.AiImageRequest;
import com.letsblog.media.testsupport.BrokenObjectMapper;
import com.letsblog.media.testsupport.FakeHttpServer;
import com.letsblog.media.testsupport.MediaWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * 画像生成ジョブの実行本体(issue #1405)。{@code @Async}はSpringプロキシ経由でしか効かないため、
 * メソッドを同期的に呼んで検証する(プロキシ経由の起動は{@code ImageGenerationJobStarterTest})。
 *
 * <p>結果はBase64ではなく生成画像のIDで{@code result_payload}へ残す(#1112のオンヒープ保持を
 * 非同期経路で再現しないため)。
 *
 * <p>issue #1819: {@code ImageGenerationService}・{@code GenerationJobClient}・{@code ImageGenerationJobTracker}は実物。
 * ComfyUI・ChatGPT画像API・ai-service・platform-serviceをフェイクHTTPサーバー、DBをリポジトリのmockが演じる。
 * 失敗の種別は、各外部通信の失敗(HTTPのステータス、接続拒否、DBの障害)から作る。
 */
@DisplayName("media-service: 画像生成ジョブランナー(issue #1405)")
class ImageGenerationJobRunnerTest {

    private static final AiImageRequest REQUEST = new AiImageRequest(
            "a cat", null, null, null, null, null, 1L, null, null, null, null, null, null, null, 1L);

    private final MediaWorld world = new MediaWorld();
    private final ImageGenerationJobRunner runner = world.jobRunner;

    @AfterEach
    void close() {
        world.close();
    }

    private JsonNode payloadOf(String status) {
        List<MediaWorld.JobUpdate> updates = world.jobUpdates(status);
        assertEquals(1, updates.size(), status + "の更新は1件");
        assertEquals(7L, updates.get(0).jobId());
        return updates.get(0).payload();
    }

    private static List<Long> ids(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asLong).toList();
    }

    @Test
    void 成功すると生成画像のIDをdoneのresult_payloadに残しBase64は含めない() {
        world.comfyReturns(2);

        runner.run(7L, REQUEST);

        JsonNode result = payloadOf("done");
        assertEquals(List.of(100L, 101L), ids(result.get("imageIds")));
        assertEquals(2, result.get("count").asInt());
        assertEquals(1, result.get("succeededRepeats").asInt());
        assertEquals(0, result.get("failedRepeats").asInt());
        assertFalse(result.toString().contains("dataBase64"));
        assertTrue(world.jobUpdates("failed").isEmpty());
    }

    @Test
    void 一部のリピートが失敗しても成功分のIDと失敗数をdoneで残す() {
        // 1リピート目は成功し、2・3リピート目が(2回続けて)失敗して打ち切る。
        world.http.on("POST", "/prompt", req ->
                world.http.requests("POST", "/prompt").size() == 1 ? null
                        : FakeHttpServer.Response.json(500, "{\"error\":\"vram\"}"));
        AiImageRequest request = new AiImageRequest(
                "a cat", null, null, null, null, null, 1L, null, null, null, 5, null, null, null, 1L);

        runner.run(7L, request);

        JsonNode result = payloadOf("done");
        assertEquals(1, result.get("succeededRepeats").asInt());
        assertEquals(4, result.get("failedRepeats").asInt());
        assertEquals(3, result.get("attemptedRepeats").asInt());
        assertTrue(result.get("aborted").asBoolean());
    }

    @Test
    void リピート回数が0なら進捗を報告せず空の結果をdoneで残す() {
        // 進捗のpercentは「完了数/総数」で、総数はリピート回数。リピートが1回も回らない要求では
        // 進捗の通知が呼ばれないので、総数が0で割る場面は無い。
        AiImageRequest request = new AiImageRequest(
                "a cat", null, null, null, null, null, 1L, null, null, null, 0, null, null, null, 1L);

        runner.run(7L, request);

        assertEquals(1, world.jobUpdates("running").size(), "開始の0%だけ");
        JsonNode result = payloadOf("done");
        assertEquals(0, result.get("count").asInt());
        assertEquals(0, result.get("attemptedRepeats").asInt());
        assertTrue(world.comfyWorkflows().isEmpty());
    }

    @Test
    void リピートが終わるたびに進捗をrunningで書く() {
        AiImageRequest request = new AiImageRequest(
                "a cat", null, null, null, null, null, 1L, null, null, null, 4, null, null, null, 1L);

        runner.run(7L, request);

        List<MediaWorld.JobUpdate> running = world.jobUpdates("running");
        assertEquals(5, running.size(), "開始(0%)と4リピート分");
        assertEquals("generating", running.get(0).payload().get("phase").asText());
        assertEquals(0, running.get(0).payload().get("percent").asInt());
        assertEquals(25, running.get(1).payload().get("percent").asInt());
        assertEquals(50, running.get(2).payload().get("percent").asInt());
        assertEquals(100, running.get(4).payload().get("percent").asInt());
    }

    @Test
    void 禁止コンテンツはfailedで種別prohibited_contentと理由を残す() {
        world.settingsFor(1L).setBlockSexualContent(true);
        AiImageRequest request = new AiImageRequest(
                "a nude woman", null, null, null, null, null, 1L, null, null, null, null, null, null, null, 1L);

        runner.run(7L, request);

        JsonNode result = payloadOf("failed");
        assertEquals("prohibited_content", result.get("errorType").asText());
        assertTrue(result.get("error").asText().contains("性的コンテンツ"));
        assertTrue(world.jobUpdates("done").isEmpty());
    }

    @Test
    void ComfyUI不到達はfailedで種別provider_unreachableと接続先を残す() {
        world.comfyUiBaseUrlIs("http://127.0.0.1:1");

        runner.run(7L, REQUEST);

        JsonNode result = payloadOf("failed");
        assertEquals("provider_unreachable", result.get("errorType").asText());
        assertTrue(result.get("error").asText().contains("http://127.0.0.1:1"));
    }

    @Test
    void プロバイダの429はfailedで種別rate_limitedを残す() {
        world.providerIs(1L, "CHATGPT");
        world.http.status("POST", "/images/generations", 429, "{\"error\":\"too many\"}");

        runner.run(7L, REQUEST);

        JsonNode result = payloadOf("failed");
        assertEquals("rate_limited", result.get("errorType").asText());
        assertTrue(result.get("error").asText().contains("429"));
    }

    @Test
    void 到達不能以外のプロバイダ障害はgeneration_failedになる() {
        // ComfyUIの接続先が未設定(AiServiceException、原因なし)。
        world.comfyUiBaseUrlIs("");

        runner.run(7L, REQUEST);

        assertEquals("generation_failed", payloadOf("failed").get("errorType").asText());
    }

    @Test
    void 原因がHTTP応答でも429以外ならgeneration_failedになる() {
        world.providerIs(1L, "CHATGPT");
        world.http.status("POST", "/images/generations", 500, "{\"error\":\"boom\"}");

        runner.run(7L, REQUEST);

        assertEquals("generation_failed", payloadOf("failed").get("errorType").asText());
    }

    @Test
    void 原因がResourceAccessExceptionでもComfyUI不到達の型でなければgeneration_failed() {
        // ChatGPT画像APIへ接続できない(接続拒否)。ChatGptImageClientはAiServiceExceptionにして返す。
        world.providerIs(1L, "CHATGPT");
        world.platformServiceClient.chatGptBaseUrl("http://127.0.0.1:1");

        runner.run(7L, REQUEST);

        assertEquals("generation_failed", payloadOf("failed").get("errorType").asText());
    }

    @Test
    void 想定外の例外もfailedにしてジョブをrunningのまま残さない() {
        doThrow(new IllegalStateException("boom")).when(world.projectImageSettingsRepository).findByProjectId(any());

        runner.run(7L, REQUEST);

        JsonNode result = payloadOf("failed");
        assertEquals("generation_failed", result.get("errorType").asText());
        assertEquals("boom", result.get("error").asText());
    }

    @Test
    void 例外のメッセージがnullでも失敗を記録できる() {
        doThrow(new IllegalStateException((String) null)).when(world.projectImageSettingsRepository)
                .findByProjectId(any());

        runner.run(7L, REQUEST);

        JsonNode result = payloadOf("failed");
        assertEquals("generation_failed", result.get("errorType").asText());
        assertEquals("null", result.get("error").asText());
    }

    @Test
    void 結果のJSON化に失敗しても空のJSONで終端通知する() {
        ImageGenerationJobRunner brokenRunner = new ImageGenerationJobRunner(
                world.imageGenerationService, world.generationJobClient, BrokenObjectMapper.create(), world.tracker);

        brokenRunner.run(7L, REQUEST);

        var done = world.http.requests("PATCH", "/api/internal/ai/generation-jobs/7").stream()
                .map(world::bodyOf).filter(b -> b.get("status").asText().equals("done")).findFirst().orElseThrow();
        assertEquals("{}", done.get("resultPayload").asText());
    }

    @Test
    void 完了後はハートビートの追跡から外れる() {
        world.tracker.track(7L);

        runner.run(7L, REQUEST);

        assertFalse(world.tracker.isTracked(7L));
    }

    @Test
    void 失敗後もハートビートの追跡から外れる() {
        world.tracker.track(7L);
        world.comfyUiBaseUrlIs("");

        runner.run(7L, REQUEST);

        assertFalse(world.tracker.isTracked(7L));
    }

    @Test
    void 進捗はトラッカーにも反映されハートビートが最新値を使う() {
        world.tracker.track(7L);
        // 2リピート目の生成の最中(ComfyUIが/promptを受けた瞬間)にハートビートが走る。
        world.http.on("POST", "/prompt", req -> {
            if (world.http.requests("POST", "/prompt").size() == 2) {
                world.tracker.heartbeat();
            }
            return null;
        });
        AiImageRequest request = new AiImageRequest(
                "a cat", null, null, null, null, null, 1L, null, null, null, 4, null, null, null, 1L);

        runner.run(7L, request);

        List<MediaWorld.JobUpdate> running = world.jobUpdates("running");
        MediaWorld.JobUpdate heartbeat = running.stream()
                .filter(u -> u.payload().has("heartbeatAt")).findFirst().orElseThrow();
        assertEquals(25, heartbeat.payload().get("percent").asInt(), "ハートビートは直近の進捗(1/4リピート)を使う");
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

    @Test
    void 完了行_成功はINFOでkey_valueの1行を出す() {
        List<ILoggingEvent> lines = completionLines(() -> runner.run(7L, REQUEST));

        assertEquals(1, lines.size(), lines.toString());
        assertEquals(Level.INFO, lines.get(0).getLevel());
        assertTrue(lines.get(0).getFormattedMessage()
                .matches("job completed: kind=image_generation job_id=7 duration_ms=\\d+ outcome=success"),
                lines.get(0).getFormattedMessage());
    }

    @Test
    void 完了行_失敗はWARN以上で例外クラス名をoutcomeに出す() {
        doThrow(new IllegalStateException("boom")).when(world.projectImageSettingsRepository).findByProjectId(any());
        List<ILoggingEvent> lines = completionLines(() -> runner.run(7L, REQUEST));

        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).getLevel().isGreaterOrEqual(Level.WARN));
        assertTrue(lines.get(0).getFormattedMessage()
                .matches("job completed: kind=image_generation job_id=7 duration_ms=\\d+ outcome=IllegalStateException"),
                lines.get(0).getFormattedMessage());
    }
}
