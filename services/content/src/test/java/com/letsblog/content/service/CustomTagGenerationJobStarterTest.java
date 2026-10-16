package com.letsblog.content.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.common.client.GenerationJobBridgeException;
import com.letsblog.common.client.GenerationJobSummary;
import com.letsblog.content.dto.GenerateCustomTagRequest;
import com.letsblog.content.testsupport.AsyncProxies;
import com.letsblog.content.testsupport.BrokenObjectMapper;
import com.letsblog.content.testsupport.ContentWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * カスタムタグ生成ジョブの受理側(issue #1409)。project-serviceのManagedSiteProvisioningJobStarterと同型。
 *
 * <p>issue #1821: GenerationJobClient・CustomTagGenerationJobRunner・AdminAuthorizationServiceは実物。
 * ai-service・identity-serviceはHTTPフェイク。{@code @Async}は、Springの実際のAOPプロキシで効かせた
 * ランナーを渡して検証する(実行枠が満杯のExecutorなら、起動が実際に{@code TaskRejectedException}になる)。
 */
@DisplayName("content-service: カスタムタグ生成ジョブの受理(issue #1409)")
class CustomTagGenerationJobStarterTest {

    private static final String JOBS_PATH = "/api/internal/ai/generation-jobs";
    private static final GenerateCustomTagRequest REQUEST =
            new GenerateCustomTagRequest("青いボタン", "blue-button", "説明", 4L);

    private ContentWorld world;
    private ThreadPoolTaskExecutor executor;

    @BeforeEach
    void setUp() {
        world = new ContentWorld().actingAsAdmin(1L);
        world.http.json("POST", "/api/internal/ai/generate", "{\"result\":\"```html\\n<b/>\\n```\"}");
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
        world.close();
    }

    /** 実行を呼び出しスレッドとは別のスレッドで行うプロキシのランナー(実運用と同じ非同期)。 */
    private CustomTagGenerationJobStarter starterWithAsyncRunner(ThreadPoolTaskExecutor taskExecutor) {
        executor = taskExecutor;
        CustomTagGenerationJobRunner asyncRunner =
                AsyncProxies.async(world.customTagGenerationJobRunner, "customTagGenerationExecutor", taskExecutor);
        return new CustomTagGenerationJobStarter(
                world.generationJobClient, asyncRunner, world.adminAuthorizationService, world.objectMapper);
    }

    private ThreadPoolTaskExecutor roomyExecutor() {
        ThreadPoolTaskExecutor roomy = new ThreadPoolTaskExecutor();
        roomy.setCorePoolSize(1);
        roomy.initialize();
        return roomy;
    }

    @Test
    @DisplayName("ジョブ種別は安定した名前 custom_tag_generation")
    void jobTypeIsStable() {
        assertEquals("custom_tag_generation", CustomTagGenerationJobStarter.JOB_TYPE);
    }

    @Test
    @DisplayName("管理者確認のあと、ユーザーのBearerでジョブを作り、ランナーを起動してジョブを返す")
    void startsJob() throws Exception {
        CustomTagGenerationJobStarter starter = starterWithAsyncRunner(roomyExecutor());

        GenerationJobSummary result = starter.start(REQUEST, "Bearer abc");

        assertEquals(9L, result.id());
        assertEquals("running", result.status());
        var create = world.http.requests("POST", JOBS_PATH).get(0);
        assertEquals("Bearer abc", create.header("Authorization"));
        JsonNode body = world.bodyOf(create);
        assertEquals("custom_tag_generation", body.get("type").asText());
        JsonNode json = world.objectMapper.readTree(body.get("requestPayload").asText());
        assertEquals("blue-button", json.get("tagName").asText());
        assertEquals("説明", json.get("description").asText());
        assertEquals(4L, json.get("projectId").asLong());
        assertEquals("青いボタン", json.get("prompt").asText());
        // ランナーは別スレッドで動き、終端通知(done)を送る
        long deadline = System.currentTimeMillis() + 5000;
        while (world.http.requests("PATCH", JOBS_PATH + "/9").isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals("done", world.bodyOf(world.http.requests("PATCH", JOBS_PATH + "/9").get(0)).get("status").asText());
    }

    @Test
    @DisplayName("管理者でなければジョブを作らず例外を伝える")
    void nonAdminIsRejected() {
        world.actingAsUser(2L);

        assertThrows(ForbiddenException.class,
                () -> world.customTagGenerationJobStarter.start(REQUEST, "Bearer abc"));

        assertTrue(world.http.requests("POST", JOBS_PATH).isEmpty());
    }

    @Test
    @DisplayName("実行枠と待ち行列が満杯なら、ジョブを queue_full の failed にして返す")
    void queueFull() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CustomTagGenerationJobStarter starter = starterWithAsyncRunner(AsyncProxies.saturated(release));
        try {
            GenerationJobSummary result = starter.start(REQUEST, "Bearer abc");

            assertEquals("failed", result.status());
            assertEquals(9L, result.id());
            var update = world.http.requests("PATCH", JOBS_PATH + "/9").get(0);
            assertEquals("failed", world.bodyOf(update).get("status").asText());
            JsonNode json = world.objectMapper.readTree(world.bodyOf(update).get("resultPayload").asText());
            assertEquals("queue_full", json.get("errorType").asText());
            assertFalse(json.get("error").asText().isBlank());
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("ジョブを作れなければ(ai-service 不達)ランナーを起動せず例外を伝える")
    void createFailurePropagates() throws Exception {
        world.http.status("POST", JOBS_PATH, 400, "{\"error\":\"down\"}");

        assertThrows(GenerationJobBridgeException.class,
                () -> world.customTagGenerationJobStarter.start(REQUEST, "Bearer abc"));

        assertTrue(world.http.requests("POST", "/api/internal/ai/generate").isEmpty());
    }

    @Test
    @DisplayName("要求内容をJSONにできなくても、空のJSONでジョブを作る")
    void jsonFailureFallsBackToEmptyObject() {
        CustomTagGenerationJobStarter starter = new CustomTagGenerationJobStarter(
                world.generationJobClient, world.customTagGenerationJobRunner, world.adminAuthorizationService,
                BrokenObjectMapper.create());

        starter.start(REQUEST, "Bearer abc");

        assertEquals("{}", world.bodyOf(world.http.requests("POST", JOBS_PATH).get(0)).get("requestPayload").asText());
    }
}
