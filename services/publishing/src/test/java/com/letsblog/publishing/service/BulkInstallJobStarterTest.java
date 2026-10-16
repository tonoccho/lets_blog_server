package com.letsblog.publishing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.common.client.GenerationJobBridgeException;
import com.letsblog.common.client.GenerationJobSummary;
import com.letsblog.publishing.config.AsyncJobConfig;
import com.letsblog.publishing.domain.BulkOperationType;
import com.letsblog.publishing.dto.ApplyToAllEnvironmentsRequest;
import com.letsblog.publishing.testsupport.AsyncProxies;
import com.letsblog.publishing.testsupport.PublishingWorld;
import com.letsblog.publishing.testsupport.PublishingWorld.JobUpdate;
import com.letsblog.publishing.testsupport.PublishingWorld.SiteSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一括インストールジョブの受理側(issue #1702)。project-serviceの ProjectEnvironmentSyncJobStarterTest と同型。
 *
 * <p>issue #1815で書き直し: BulkManagementService・BulkUploadStorageService・CurrentActorService・GenerationJobClient・
 * BulkInstallJobRunnerは実物を使い、外部との通信(identity-service・project-service・provision-agent・ai-serviceへの
 * HTTP)だけを{@code FakeHttpServer}で差し替える。ランナーの{@code @Async}は、Springの実際のAOPプロキシ
 * ({@code AsyncProxies})と本番と同じ{@code AsyncJobConfig}のExecutorで動かす。
 */
@DisplayName("publishing-service: 全環境インストールジョブの受理(issue #1702)")
class BulkInstallJobStarterTest {

    private PublishingWorld world;
    private ThreadPoolTaskExecutor executor;
    private BulkInstallJobRunner runner;
    private BulkInstallJobStarter starter;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld();
        world.stubActor("Bearer abc", 5L, "user", "u@example.com").asUser("Bearer abc");
        world.agent.json("POST", "/bulk-management", "{\"status\":\"success\"}");
        world.agent.json("POST", "/bulk-management/upload", "{\"status\":\"success\"}");
        world.bindEnvironments(3L, "test", SiteSpec.managed(11L, "local-site"), null, null);
        executor = (ThreadPoolTaskExecutor) new AsyncJobConfig().bulkInstallExecutor();
        runner = AsyncProxies.async(
                new BulkInstallJobRunner(world.bulkManagementService, world.bulkUploadStorageService,
                        world.generationJobClient, world.mapper),
                "bulkInstallExecutor", executor);
        starter = starter(world.bulkUploadStorageService);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
        world.close();
    }

    private BulkInstallJobStarter starter(BulkUploadStorageService storage) {
        return new BulkInstallJobStarter(world.generationJobClient, runner, world.bulkManagementService, storage,
                world.currentActorService, world.mapper);
    }

    private static MockMultipartFile zip() {
        return new MockMultipartFile("file", "my-plugin.zip", "application/zip", new byte[] {1, 2, 3});
    }

    /** 非同期のランナーが終端の通知を送るのを待つ。 */
    private List<JobUpdate> awaitTerminal() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            List<JobUpdate> updates = world.jobUpdates();
            if (updates.stream().anyMatch(u -> u.status().equals("done") || u.status().equals("failed"))) {
                return updates;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("ジョブの終端の通知が来ませんでした: " + world.jobUpdates());
    }

    private JsonNode createdPayload() throws Exception {
        var requests = world.ai.requests("POST", "/api/internal/ai/generation-jobs");
        assertEquals(1, requests.size());
        return world.mapper.readTree(world.mapper.readTree(requests.get(0).body()).get("requestPayload").asText());
    }

    @Test
    @DisplayName("ジョブ種別は安定した非AIの名前")
    void jobTypesAreStable() {
        assertEquals("bulk_slug_install", BulkInstallJobStarter.SLUG_JOB_TYPE);
        assertEquals("bulk_zip_install", BulkInstallJobStarter.ZIP_JOB_TYPE);
    }

    @Test
    @DisplayName("slug: リクエストスレッドで操作者を解決し、利用者のBearerでジョブを作り、同じ操作者IDをランナーへ渡す")
    void startsSlugJob() throws Exception {
        GenerationJobSummary result = starter.startSlugInstall(
                3L, new ApplyToAllEnvironmentsRequest(BulkOperationType.PLUGIN_INSTALL, "akismet"));

        assertEquals("bulk_slug_install", result.type());
        assertEquals("running", result.status());
        assertEquals("Bearer abc",
                world.ai.requests("POST", "/api/internal/ai/generation-jobs").get(0).header("Authorization"));
        JsonNode json = createdPayload();
        // キューUIが結果の遷移先を決めるのに projectId を読む。
        assertEquals(3L, json.get("projectId").asLong());
        assertEquals("PLUGIN_INSTALL", json.get("operationType").asText());
        assertEquals("akismet", json.get("value").asText());
        // ランナーは受け取った操作者IDで全環境へインストールし、ジョブを done にする。
        List<JobUpdate> updates = awaitTerminal();
        JobUpdate done = updates.get(updates.size() - 1);
        assertEquals("done", done.status());
        assertEquals(result.id(), done.id());
        assertEquals(1, done.result().get("logs").size());
        assertTrue(world.agent.requests("POST", "/bulk-management").get(0).bodyText().contains("akismet"));
    }

    @Test
    @DisplayName("slug: 入力が誤っていれば、ジョブを作らず検証の例外をそのまま伝える")
    void slugValidationFailureCreatesNoJob() {
        assertThrows(IllegalArgumentException.class, () -> starter.startSlugInstall(
                3L, new ApplyToAllEnvironmentsRequest(BulkOperationType.PLUGIN_INSTALL, " ")));

        assertTrue(world.ai.requests().isEmpty());
        assertTrue(world.agent.requests().isEmpty());
    }

    @Test
    @DisplayName("zip: 受理スレッドで保存し、保存先・sha256・元のファイル名をジョブの要求内容に載せ、ランナーへ渡す")
    void startsZipJob() throws Exception {
        GenerationJobSummary result = starter.startZipInstall(3L, BulkOperationType.THEME_INSTALL, zip());

        assertEquals("bulk_zip_install", result.type());
        String sha256 = com.letsblog.publishing.cms.MediaContentHash.sha256Hex(new byte[] {1, 2, 3});
        JsonNode json = createdPayload();
        assertEquals(3L, json.get("projectId").asLong());
        assertEquals("THEME_INSTALL", json.get("operationType").asText());
        assertEquals("3/" + sha256 + ".zip", json.get("storagePath").asText());
        assertEquals(sha256, json.get("sha256").asText());
        assertEquals("my-plugin.zip", json.get("originalFilename").asText());
        assertEquals("Bearer abc",
                world.ai.requests("POST", "/api/internal/ai/generation-jobs").get(0).header("Authorization"));
        List<JobUpdate> updates = awaitTerminal();
        assertEquals("done", updates.get(updates.size() - 1).status());
        assertEquals(1, world.agent.requests("POST", "/bulk-management/upload").size());
    }

    @Test
    @DisplayName("zip: 入力が誤っていれば、保存もジョブ作成もしない")
    void zipValidationFailureStoresNothing() throws Exception {
        MockMultipartFile notZip = new MockMultipartFile("file", "p.txt", "text/plain", new byte[] {1});

        assertThrows(IllegalArgumentException.class,
                () -> starter.startZipInstall(3L, BulkOperationType.PLUGIN_INSTALL, notZip));

        assertTrue(world.ai.requests().isEmpty());
        assertThrows(IOException.class, () -> world.bulkUploadStorageService.load("3/anything.zip"));
    }

    @Test
    @DisplayName("zip: 保存に失敗すればジョブを作らず IOException を伝える")
    void zipStoreFailurePropagates() throws Exception {
        // 保存先のルートが通常のファイルなので、プロジェクトのディレクトリを作れない。
        Path file = Files.createTempFile("lbs-not-a-directory", ".tmp");
        try {
            BulkInstallJobStarter brokenStorageStarter = starter(new BulkUploadStorageService(file.toString()));

            assertThrows(IOException.class,
                    () -> brokenStorageStarter.startZipInstall(3L, BulkOperationType.PLUGIN_INSTALL, zip()));

            assertTrue(world.ai.requests().isEmpty());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** 実行枠(core 1 / max 2)と待ち行列(5)を、終わらないタスクで埋める。 */
    private CountDownLatch saturate(Executor pool) throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < 7; i++) {
            pool.execute(() -> {
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        return release;
    }

    @Test
    @DisplayName("実行枠と待ち行列が満杯なら、ジョブを queue_full の failed にして返す(slug)")
    void queueFullForSlug() throws Exception {
        CountDownLatch release = saturate(executor);
        try {
            GenerationJobSummary result = starter.startSlugInstall(
                    3L, new ApplyToAllEnvironmentsRequest(BulkOperationType.PLUGIN_INSTALL, "akismet"));

            assertEquals("failed", result.status());
            JobUpdate failed = world.jobUpdates().get(0);
            assertEquals("failed", failed.status());
            assertEquals(result.id(), failed.id());
            assertEquals("queue_full", failed.result().get("errorType").asText());
            assertFalse(failed.result().get("error").asText().isBlank());
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("実行枠と待ち行列が満杯なら、ジョブを queue_full の failed にして返す(zip)")
    void queueFullForZip() throws Exception {
        CountDownLatch release = saturate(executor);
        try {
            GenerationJobSummary result = starter.startZipInstall(3L, BulkOperationType.PLUGIN_INSTALL, zip());

            assertEquals("failed", result.status());
            assertEquals("queue_full", world.jobUpdates().get(0).result().get("errorType").asText());
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("ジョブを作れなければ(ai-service 不達)ランナーを起動せず例外を伝える")
    void createFailurePropagates() {
        // 認可されていない(401)応答。サーキットブレーカーに数えられない失敗なので、他のテストへ影響しない。
        world.ai.status("POST", "/api/internal/ai/generation-jobs", 401, "{\"error\":\"unauthorized\"}");

        assertThrows(GenerationJobBridgeException.class, () -> starter.startSlugInstall(
                3L, new ApplyToAllEnvironmentsRequest(BulkOperationType.PLUGIN_INSTALL, "akismet")));

        assertTrue(world.agent.requests().isEmpty(), "ランナーは起動しない");
        assertTrue(world.jobUpdates().isEmpty());
    }

    @Test
    @DisplayName("ジョブの要求内容のJSON化に失敗しても、空のJSONで受理を続ける")
    void payloadSerializationFailureFallsBackToEmptyJson() throws Exception {
        // 全ての文字列を書き出せないObjectMapper(実物のObjectMapperに、必ず失敗するシリアライザを登録したもの)。
        com.fasterxml.jackson.databind.ObjectMapper broken = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.module.SimpleModule module = new com.fasterxml.jackson.databind.module.SimpleModule();
        module.addSerializer(String.class, new com.fasterxml.jackson.databind.ser.std.StdSerializer<>(String.class) {
            @Override
            public void serialize(String value, com.fasterxml.jackson.core.JsonGenerator generator,
                    com.fasterxml.jackson.databind.SerializerProvider provider) throws IOException {
                throw new IOException("boom");
            }
        });
        broken.registerModule(module);
        BulkInstallJobStarter brokenStarter = new BulkInstallJobStarter(world.generationJobClient, runner,
                world.bulkManagementService, world.bulkUploadStorageService, world.currentActorService, broken);

        brokenStarter.startSlugInstall(
                3L, new ApplyToAllEnvironmentsRequest(BulkOperationType.PLUGIN_INSTALL, "akismet"));

        var request = world.ai.requests("POST", "/api/internal/ai/generation-jobs").get(0);
        assertEquals("{}", world.mapper.readTree(request.body()).get("requestPayload").asText());
    }
}
