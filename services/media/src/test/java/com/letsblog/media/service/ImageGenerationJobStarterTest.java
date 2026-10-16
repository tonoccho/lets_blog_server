package com.letsblog.media.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.common.client.GenerationJobSummary;
import com.letsblog.media.dto.AiImageRequest;
import com.letsblog.media.testsupport.AsyncProxies;
import com.letsblog.media.testsupport.BrokenObjectMapper;
import com.letsblog.media.testsupport.MediaWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 画像生成を非同期ジョブとして受理する側(issue #1405)。ジョブを作って即座に返し、
 * 生成本体は別クラスのランナーへ渡す(Springの{@code @Async}は自己呼び出しに効かない)。
 *
 * <p>issue #1819: {@code ImageGenerationService}・{@code ImageGenerationJobRunner}・{@code GenerationJobClient}・
 * {@code ImageGenerationJobTracker}はすべて実物。ランナーの{@code @Async}はSpringの実際のAOPプロキシで効かせ、
 * 実行を後に回す(タスクを溜めるだけの)Executorと、実行枠が満杯の実際の{@code ThreadPoolTaskExecutor}を使い分ける。
 * ai-service・ComfyUIはフェイクHTTPサーバーが演じる。
 */
@DisplayName("media-service: 画像生成ジョブの受理(issue #1405)")
class ImageGenerationJobStarterTest {

    private static final AiImageRequest REQUEST = AiImageRequest.withDefaults("a cat");

    private final MediaWorld world = new MediaWorld();
    private final List<Runnable> deferred = new ArrayList<>();

    @AfterEach
    void close() {
        world.close();
    }

    /** ランナーの{@code @Async}を、渡されたExecutorで効かせたスターターを組む。 */
    private ImageGenerationJobStarter starterWith(Executor executor) {
        ImageGenerationJobRunner asyncRunner =
                AsyncProxies.async(world.jobRunner, "imageGenerationExecutor", executor);
        return new ImageGenerationJobStarter(
                world.imageGenerationService, asyncRunner, world.generationJobClient, world.objectMapper, world.tracker);
    }

    private ImageGenerationJobStarter deferredStarter() {
        return starterWith(deferred::add);
    }

    @Test
    void ジョブを作ってジョブIDを返しランナーへ渡す() {
        ImageGenerationJobStarter starter = deferredStarter();

        GenerationJobSummary result = starter.start(REQUEST, "Bearer t");

        assertEquals(9L, result.id());
        assertEquals("running", result.status());
        assertEquals(1, deferred.size(), "ランナーの実行がExecutorへ渡された");
        assertTrue(world.comfyWorkflows().isEmpty(), "受理の時点では生成を始めない");
        MediaWorld.JobCreate created = world.jobCreates().get(0);
        assertEquals("image_generation", created.type());
        assertEquals("a cat", created.requestPayload().get("prompt").asText());
        assertEquals("COMFYUI", created.requestPayload().get("provider").asText());
        assertEquals("Bearer t", world.http.requests("POST", "/api/internal/ai/generation-jobs").get(0)
                .header("Authorization"));

        deferred.get(0).run();

        assertEquals(1, world.comfyWorkflows().size(), "渡されたタスクを実行すると生成が行われる");
        assertEquals(1, world.jobUpdates("done").size());
        assertEquals(9L, world.jobUpdates("done").get(0).jobId());
    }

    @Test
    void 受け付けられない要求はジョブを作らずそのまま例外にする() {
        ImageGenerationJobStarter starter = deferredStarter();
        world.providerIs(1L, "CHATGPT");
        AiImageRequest tooMany = new AiImageRequest(
                "a cat", null, null, null, null, null, null, null, null, 11, null, null, null, null, 1L);

        assertThrows(UnsupportedBatchSizeException.class, () -> starter.start(tooMany, "Bearer t"));

        assertTrue(world.jobCreates().isEmpty());
        assertTrue(deferred.isEmpty());
    }

    @Test
    void 実行枠が満杯ならジョブをfailedにして理由を残しジョブを返す() {
        CountDownLatch release = new CountDownLatch(1);
        try {
            world.providerIs(1L, "CHATGPT");
            ImageGenerationJobStarter starter = starterWith(AsyncProxies.saturated(release));
            AiImageRequest request = new AiImageRequest(
                    "a cat", null, null, null, null, null, null, null, null, null, null, null, null, null, 1L);

            GenerationJobSummary result = starter.start(request, "Bearer t");

            assertEquals(9L, result.id());
            assertEquals("failed", result.status(), "応答が実際の状態(failed)と食い違ってはならない");
            assertFalse(world.tracker.isTracked(9L));
            JsonNode failure = world.jobUpdates("failed").get(0).payload();
            assertEquals("queue_full", failure.get("errorType").asText());
            assertEquals("CHATGPT", world.jobCreates().get(0).requestPayload().get("provider").asText());
        } finally {
            release.countDown();
        }
    }

    @Test
    void 指定した枚数と回数をリクエストのペイロードに残す() {
        ImageGenerationJobStarter starter = deferredStarter();
        AiImageRequest request = new AiImageRequest(
                "a cat", null, null, null, null, null, null, null, null, 4, 3, null, null, null, null);

        starter.start(request, "Bearer t");

        JsonNode json = world.jobCreates().get(0).requestPayload();
        assertEquals(4, json.get("batchSize").asInt());
        assertEquals(3, json.get("batchCount").asInt());
    }

    @Test
    void 枚数と回数を省略すると1として残す() {
        ImageGenerationJobStarter starter = deferredStarter();

        starter.start(REQUEST, "Bearer t");

        JsonNode json = world.jobCreates().get(0).requestPayload();
        assertEquals(1, json.get("batchSize").asInt());
        assertEquals(1, json.get("batchCount").asInt());
    }

    @Test
    void JSON化に失敗しても空オブジェクトで受理を続ける() {
        ImageGenerationJobStarter brokenStarter = new ImageGenerationJobStarter(
                world.imageGenerationService,
                AsyncProxies.async(world.jobRunner, "imageGenerationExecutor", deferred::add),
                world.generationJobClient, BrokenObjectMapper.create(), world.tracker);

        brokenStarter.start(REQUEST, "Bearer t");

        var sent = world.bodyOf(world.http.requests("POST", "/api/internal/ai/generation-jobs").get(0));
        assertEquals("{}", sent.get("requestPayload").asText());
    }

    @Test
    void 受理したジョブはランナーへ渡す前にハートビートの追跡へ入る() {
        ImageGenerationJobStarter starter = deferredStarter();

        starter.start(REQUEST, "Bearer t");

        assertTrue(world.tracker.isTracked(9L));
        deferred.get(0).run();
        assertFalse(world.tracker.isTracked(9L), "完了すると追跡から外れる");
    }
}
