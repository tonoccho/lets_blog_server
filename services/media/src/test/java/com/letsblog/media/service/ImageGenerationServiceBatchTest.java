package com.letsblog.media.service;

import com.letsblog.media.dto.AiImageRequest;
import com.letsblog.media.service.ImageGenerationService.BatchOutcome;
import com.letsblog.media.ai.ImageProvider;
import com.letsblog.media.testsupport.FakeHttpServer;
import com.letsblog.media.testsupport.MediaWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成本体{@code generateBatch}(issue #1405)。非同期ジョブランナーが、受理時に作られたジョブの中で呼ぶ。
 *
 * <p>同期経路{@code generateImage}が自前でジョブを作るのに対し、こちらは既に作られたジョブの
 * 中で動くため、ジョブを作らず、Base64を保持せず、リピート完了を通知する。
 *
 * <p>issue #1819: {@code ImageGenerationService}とその協力者はすべて実物で、ComfyUI・ChatGPT画像API・ai-service・
 * platform-serviceをフェイクHTTPサーバー、DBをリポジトリのmockが演じる({@code ImageGenerationServiceTest}と同じ世界)。
 */
@DisplayName("media-service: 生成本体generateBatch(issue #1405)")
class ImageGenerationServiceBatchTest {

    private final MediaWorld world = new MediaWorld();
    private final ImageGenerationService service = world.imageGenerationService;

    @AfterEach
    void close() {
        world.close();
    }

    private static AiImageRequest repeated(int batchSize, int batchCount) {
        return new AiImageRequest(
                "a cat", null, null, null, null, null, 1L, null, null, batchSize, batchCount, null, null, null, null);
    }

    @Test
    void ジョブを作らず保存した画像のIDを返す() {
        world.comfyReturns(2);

        BatchOutcome outcome = service.generateBatch(repeated(2, 1), false, (done, total) -> { });

        assertEquals(List.of(100L, 101L), outcome.images().stream().map(i -> i.id()).toList());
        assertEquals(1, outcome.succeededRepeats());
        assertEquals(0, outcome.failedRepeats());
        assertEquals(false, outcome.aborted());
        assertTrue(world.jobCreates().isEmpty());
        assertTrue(world.jobUpdates().isEmpty());
    }

    @Test
    void includeImageDataがfalseならBase64を保持しない() {
        BatchOutcome outcome = service.generateBatch(repeated(1, 1), false, (done, total) -> { });

        assertNull(outcome.images().get(0).dataBase64());
    }

    @Test
    void includeImageDataがtrueならBase64を返す() {
        BatchOutcome outcome = service.generateBatch(repeated(1, 1), true, (done, total) -> { });

        assertNotNull(outcome.images().get(0).dataBase64());
    }

    @Test
    void リピートが終わるたびに完了数と総数を通知する() {
        List<String> seen = new ArrayList<>();

        service.generateBatch(repeated(1, 3), false, (done, total) -> seen.add(done + "/" + total));

        assertEquals(List.of("1/3", "2/3", "3/3"), seen);
    }

    @Test
    void 途中で失敗しても成功分を返し失敗も通知に含める() {
        world.http.on("POST", "/prompt", req -> world.http.requests("POST", "/prompt").size() == 2
                ? FakeHttpServer.Response.json(500, "{\"error\":\"x\"}") : null);
        List<String> seen = new ArrayList<>();

        BatchOutcome outcome = service.generateBatch(repeated(1, 3), false, (done, total) -> seen.add(done + "/" + total));

        assertEquals(2, outcome.succeededRepeats());
        assertEquals(1, outcome.failedRepeats());
        assertEquals(3, outcome.attemptedRepeats());
        assertEquals(3, seen.size());
    }

    @Test
    void 全リピートが失敗したら最初の失敗を投げる() {
        java.util.concurrent.atomic.AtomicInteger call = new java.util.concurrent.atomic.AtomicInteger();
        world.http.on("POST", "/prompt", req -> FakeHttpServer.Response.json(
                500, "{\"error\":\"" + (call.incrementAndGet() == 1 ? "first" : "second") + "\"}"));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.generateBatch(repeated(1, 2), false, (done, total) -> { }));

        assertTrue(e.getMessage().contains("first"), e.getMessage());
        assertFalse(e.getMessage().contains("second"));
    }

    @Test
    void 禁止コンテンツは生成前にそのまま投げる() {
        world.settingsFor(1L).setBlockSexualContent(true);
        AiImageRequest nude = new AiImageRequest(
                "a nude woman", null, null, null, null, null, 1L, null, null, 1, 1, null, null, null, 1L);

        assertThrows(ProhibitedContentException.class,
                () -> service.generateBatch(nude, false, (done, total) -> { }));

        assertTrue(world.comfyWorkflows().isEmpty());
    }

    @Test
    void requireAcceptableは受理できるプロバイダを返す() {
        assertEquals(ImageProvider.COMFYUI, service.requireAcceptable(repeated(2, 1)));
    }

    @Test
    void requireAcceptableはプロバイダの上限を超える枚数を拒否する() {
        world.providerIs(1L, "CHATGPT");
        AiImageRequest tooMany = new AiImageRequest(
                "a cat", null, null, null, null, null, 1L, null, null, 11, 1, null, null, null, 1L);

        UnsupportedBatchSizeException e = assertThrows(UnsupportedBatchSizeException.class,
                () -> service.requireAcceptable(tooMany));

        assertTrue(e.getMessage().contains("CHATGPT"));
    }

    @Test
    void requireAcceptableは枚数未指定でも1枚として扱い受理する() {
        assertEquals(ImageProvider.COMFYUI, service.requireAcceptable(AiImageRequest.withDefaults("a cat")));
    }

    @Test
    void 連続して失敗したら残りを打ち切りabortedを立てる() {
        world.http.on("POST", "/prompt", req -> world.http.requests("POST", "/prompt").size() >= 2
                ? FakeHttpServer.Response.json(500, "{\"error\":\"x\"}") : null);

        BatchOutcome outcome = service.generateBatch(repeated(1, 4), false, (done, total) -> { });

        assertEquals(3, outcome.attemptedRepeats());
        assertEquals(3, outcome.failedRepeats());
        assertTrue(outcome.aborted());
    }
}
