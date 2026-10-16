package com.letsblog.project.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.common.client.GenerationJobBridgeException;
import com.letsblog.common.client.GenerationJobSummary;
import com.letsblog.project.dto.SyncEnvironmentRequest;
import com.letsblog.project.repository.ProjectRepository;
import com.letsblog.project.testsupport.AsyncProxies;
import com.letsblog.project.testsupport.ProjectWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 環境間同期ジョブの受理側(issue #1697)。ManagedSiteProvisioningJobStarterTest と同型。
 *
 * <p>issue #1817: ランナー・同期サービス・トラッカー・GenerationJobClient・CurrentActorService は実物。ランナーは
 * Springの実際のAOPプロキシ(@Async)で包み、Executorを差し替えて「実行を後に回す」「満杯で拒否する」を再現する。
 */
@DisplayName("project-service: 環境間同期ジョブの受理(issue #1697)")
class ProjectEnvironmentSyncJobStarterTest {

    private static final SyncEnvironmentRequest REQUEST =
            new SyncEnvironmentRequest("test", "local", List.of("db", "media"));

    private final ProjectWorld world = new ProjectWorld();
    private final ProjectRepository projectRepository = Mockito.mock(ProjectRepository.class);
    private final List<Runnable> deferred = new ArrayList<>();
    private JobHeartbeatTracker tracker;
    private ProjectEnvironmentSyncJobRunner realRunner;
    private ProjectEnvironmentSyncJobStarter starter;

    @BeforeEach
    void setUp() {
        tracker = new JobHeartbeatTracker(world.generationJobClient, world.objectMapper);
        ProjectEnvironmentSyncService syncService = new ProjectEnvironmentSyncService(projectRepository,
                world.siteRepository, world.siteService, world.wordPressSyncClient, world.cmsBridgeClient,
                world.identityBridgeClient, world.currentActorService);
        realRunner = new ProjectEnvironmentSyncJobRunner(
                syncService, world.currentActorService, world.generationJobClient, world.objectMapper, tracker);
        starterWith(deferred::add);
        world.actingAs(5L, "admin", "a@example.com").signedInAs("sub-5");
        world.request.addHeader("X-Forwarded-For", "203.0.113.9");
        world.request.addHeader("User-Agent", "ua");
    }

    private void starterWith(java.util.concurrent.Executor executor) {
        ProjectEnvironmentSyncJobRunner asyncRunner = AsyncProxies.async(realRunner, "environmentSyncExecutor", executor);
        starter = new ProjectEnvironmentSyncJobStarter(
                world.generationJobClient, asyncRunner, world.currentActorService, world.objectMapper, tracker);
    }

    @AfterEach
    void close() {
        world.close();
    }

    @Test
    @DisplayName("ジョブ種別は安定した非AIの名前 environment_sync")
    void jobTypeIsStable() {
        assertEquals("environment_sync", ProjectEnvironmentSyncJobStarter.JOB_TYPE);
    }

    @Test
    @DisplayName("リクエストスレッドで操作者を取り、ユーザーのBearerでジョブを作り、同じ操作者をランナーへ渡す")
    void startsJobWithActorResolvedInRequestThread() throws Exception {
        world.jobCreated(21L, "environment_sync");
        AtomicReference<String> actorSeenByRunner = new AtomicReference<>();
        // ランナーの実行(後で流す)の中で、同じ操作者が引けること。
        when(projectRepository.findById(3L)).thenAnswer(inv -> {
            actorSeenByRunner.set(world.currentActorService.getCurrentActorId() + "/"
                    + world.currentActorService.getCurrentActorKeycloakSub() + "/"
                    + world.currentActorService.getRemoteIp() + "/" + world.currentActorService.getUserAgent());
            return Optional.empty();
        });

        GenerationJobSummary result = starter.start(3L, REQUEST);

        assertEquals(21L, result.id());
        assertEquals("running", result.status());
        var create = world.http.requests("POST", "/api/internal/ai/generation-jobs").get(0);
        assertEquals("Bearer token-5", create.header("Authorization"));
        JsonNode body = world.bodyOf(create);
        assertEquals("environment_sync", body.get("type").asText());
        JsonNode json = world.objectMapper.readTree(body.get("requestPayload").asText());
        // キューUIが結果の遷移先(プロジェクトの設定タブ)を決めるのに projectId を読む。
        assertEquals(3L, json.get("projectId").asLong());
        assertEquals("test", json.get("from").asText());
        assertEquals("local", json.get("to").asText());
        assertEquals("db", json.get("targets").get(0).asText());
        assertEquals("media", json.get("targets").get(1).asText());
        assertEquals(1, deferred.size(), "ランナーは受理のスレッドでは動かさず、Executorへ渡す");
        deferred.forEach(Runnable::run);
        assertEquals("5/sub-5/203.0.113.9/ua", actorSeenByRunner.get());
    }

    @Test
    @DisplayName("実行枠と待ち行列が満杯なら、ジョブを queue_full の failed にして返す")
    void queueFull() {
        world.jobCreated(22L, "environment_sync");
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolTaskExecutor saturated = AsyncProxies.saturated(release);
        try {
            starterWith(saturated);

            GenerationJobSummary result = starter.start(3L, REQUEST);

            assertEquals("failed", result.status());
            assertEquals(22L, result.id());
            var failed = world.jobUpdates(22L, "failed");
            assertEquals(1, failed.size());
            assertEquals("queue_full", failed.get(0).payload().get("errorType").asText());
            assertFalse(failed.get(0).payload().get("error").asText().isBlank());
        } finally {
            release.countDown();
            saturated.shutdown();
        }
    }

    @Test
    @DisplayName("ジョブを作れなければ(ai-service 不達)ランナーを起動せず例外を伝える")
    void createFailurePropagates() {
        world.fail("POST", "/api/internal/ai/generation-jobs", 500);

        assertThrows(GenerationJobBridgeException.class, () -> starter.start(3L, REQUEST));

        assertTrue(deferred.isEmpty());
    }

    @Test
    @DisplayName("ランナーへ渡す前に追跡を始める(順番待ちの間もハートビートが打たれる。#1724)")
    void tracksBeforeRunning() {
        world.jobCreated(31L, "environment_sync");

        starter.start(3L, REQUEST);

        assertTrue(tracker.isTracked(31L), "ランナーが動き出す前(順番待ち)でも追跡されている");
        assertEquals(1, deferred.size());
        tracker.heartbeat();
        assertEquals("queued", world.jobUpdates(31L, "running").get(0).payload().get("phase").asText());
    }

    @Test
    @DisplayName("満杯で拒否されたら failed の書き込みを tracker 経由で行い、追跡を外す(#1724)")
    void queueFullCompletesThroughTracker() {
        world.jobCreated(32L, "environment_sync");
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolTaskExecutor saturated = AsyncProxies.saturated(release);
        try {
            starterWith(saturated);

            starter.start(3L, REQUEST);

            assertFalse(tracker.isTracked(32L));
            assertEquals(1, world.jobUpdates(32L, "failed").size());
        } finally {
            release.countDown();
            saturated.shutdown();
        }
    }
}
