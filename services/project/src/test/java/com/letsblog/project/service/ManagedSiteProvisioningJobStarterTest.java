package com.letsblog.project.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.common.client.GenerationJobBridgeException;
import com.letsblog.common.client.GenerationJobSummary;
import com.letsblog.project.dto.CreateManagedWordPressSiteRequest;
import com.letsblog.project.testsupport.AsyncProxies;
import com.letsblog.project.testsupport.ProjectWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * サイト自動構築ジョブの受理側(issue #1479)。ジョブを作って即座に返し、実行は{@code @Async}のランナーへ渡す。
 *
 * <p>issue #1817: ランナー・トラッカー・GenerationJobClient・CurrentActorService は実物。ランナーはSpringの実際のAOP
 * プロキシ(@Async)で包み、Executorを差し替えて「実行を後に回す」「満杯で拒否する」を再現する。HTTPの向こう
 * (ai-service)はフェイクHTTPサーバーが演じる。
 */
@DisplayName("project-service: サイト自動構築ジョブの受理(issue #1479)")
class ManagedSiteProvisioningJobStarterTest {

    private static final CreateManagedWordPressSiteRequest REQUEST = new CreateManagedWordPressSiteRequest(
            "Name", "my-site", "Title", "admin", "S3cret-admin@example.com", "S3cret-pw", "ja", 4L);

    private final ProjectWorld world = new ProjectWorld();
    private final List<Runnable> deferred = new ArrayList<>();
    private JobHeartbeatTracker tracker;
    private ManagedSiteProvisioningJobRunner realRunner;
    private ManagedSiteProvisioningJobStarter starter;

    @BeforeEach
    void setUp() {
        tracker = new JobHeartbeatTracker(world.generationJobClient, world.objectMapper);
        WordPressSiteProvisioningService provisioningService = new WordPressSiteProvisioningService(
                world.wordPressProvisioningClient, world.wordPressSyncClient, world.siteService,
                world.siteRepository, world.domainEventPublisher);
        realRunner = new ManagedSiteProvisioningJobRunner(provisioningService, world.currentActorService,
                world.generationJobClient, world.objectMapper, tracker);
        starterWith(deferred::add);
        world.actingAs(5L, "admin", "a@example.com").signedInAs("sub-5");
        world.request.addHeader("X-Forwarded-For", "203.0.113.9");
        world.request.addHeader("User-Agent", "ua");
    }

    private void starterWith(java.util.concurrent.Executor executor) {
        ManagedSiteProvisioningJobRunner asyncRunner = AsyncProxies.async(realRunner, "siteProvisioningExecutor", executor);
        starter = new ManagedSiteProvisioningJobStarter(
                world.generationJobClient, asyncRunner, world.currentActorService, world.objectMapper, tracker);
    }

    @AfterEach
    void close() {
        world.close();
    }

    @Test
    @DisplayName("ジョブ種別は安定した非AIの名前 site_provisioning")
    void jobTypeIsStable() {
        assertEquals("site_provisioning", ManagedSiteProvisioningJobStarter.JOB_TYPE);
    }

    @Test
    @DisplayName("リクエストスレッドで操作者を取り、ユーザーのBearerでジョブを作り、同じ操作者をランナーへ渡す")
    void startsJobWithActorResolvedInRequestThread() throws Exception {
        world.jobCreated(11L, "site_provisioning");
        AtomicReference<String> actorSeenByRunner = new AtomicReference<>();
        // ランナーの実行(後で流す)の中で、同じ操作者が引けること。
        org.mockito.Mockito.when(world.siteRepository.existsBySiteKey(anyString())).thenAnswer(inv -> {
            actorSeenByRunner.set(world.currentActorService.getCurrentActorId() + "/"
                    + world.currentActorService.getCurrentActorKeycloakSub() + "/"
                    + world.currentActorService.getRemoteIp() + "/" + world.currentActorService.getUserAgent());
            throw new IllegalStateException("stop");
        });

        GenerationJobSummary result = starter.start(REQUEST);

        assertEquals(11L, result.id());
        assertEquals("running", result.status());
        var create = world.http.requests("POST", "/api/internal/ai/generation-jobs").get(0);
        assertEquals("Bearer token-5", create.header("Authorization"));
        JsonNode json = world.bodyOf(create);
        assertEquals("site_provisioning", json.get("type").asText());
        JsonNode payload = world.objectMapper.readTree(json.get("requestPayload").asText());
        assertEquals("my-site", payload.get("siteKey").asText());
        assertEquals("Name", payload.get("name").asText());
        assertEquals(4L, payload.get("templateSiteId").asLong());
        // 管理者パスワードとメールはジョブの要求内容(誰でも読める)へ残さない。
        assertFalse(json.get("requestPayload").asText().contains("S3cret-pw"));
        assertFalse(payload.has("adminPassword"));
        assertFalse(payload.has("adminEmail"));
        assertEquals(1, deferred.size(), "ランナーは受理のスレッドでは動かさず、Executorへ渡す");
        deferred.forEach(Runnable::run);
        assertEquals("5/sub-5/203.0.113.9/ua", actorSeenByRunner.get());
    }

    @Test
    @DisplayName("実行枠と待ち行列が満杯なら、ジョブを queue_full の failed にして返す")
    void queueFull() {
        world.jobCreated(12L, "site_provisioning");
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolTaskExecutor saturated = AsyncProxies.saturated(release);
        try {
            starterWith(saturated);

            GenerationJobSummary result = starter.start(REQUEST);

            assertEquals("failed", result.status());
            assertEquals(12L, result.id());
            var failed = world.jobUpdates(12L, "failed");
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

        assertThrows(GenerationJobBridgeException.class, () -> starter.start(REQUEST));

        assertTrue(deferred.isEmpty());
        assertFalse(tracker.isTracked(0L));
    }

    @Test
    @DisplayName("ランナーへ渡す前に追跡を始める(順番待ちの間もハートビートが打たれる。#1724)")
    void tracksBeforeRunning() {
        world.jobCreated(31L, "site_provisioning");

        starter.start(REQUEST);

        assertTrue(tracker.isTracked(31L), "ランナーが動き出す前(順番待ち)でも追跡されている");
        assertEquals(1, deferred.size());
        tracker.heartbeat();
        assertEquals("queued", world.jobUpdates(31L, "running").get(0).payload().get("phase").asText());
    }

    @Test
    @DisplayName("満杯で拒否されたら failed の書き込みを tracker 経由で行い、追跡を外す(#1724)")
    void queueFullCompletesThroughTracker() {
        world.jobCreated(32L, "site_provisioning");
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolTaskExecutor saturated = AsyncProxies.saturated(release);
        try {
            starterWith(saturated);

            starter.start(REQUEST);

            assertFalse(tracker.isTracked(32L));
            assertEquals(1, world.jobUpdates(32L, "failed").size());
        } finally {
            release.countDown();
            saturated.shutdown();
        }
    }

    @Test
    @DisplayName("満杯で拒否されたら、ジョブIDと queue_full を含む WARN を1行出す(#1768)")
    void queueFullLogsWarn() {
        world.jobCreated(41L, "site_provisioning");
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolTaskExecutor saturated = AsyncProxies.saturated(release);
        Logger logger = (Logger) LoggerFactory.getLogger(ManagedSiteProvisioningJobStarter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            starterWith(saturated);
            starter.start(REQUEST);
        } finally {
            logger.detachAppender(appender);
            release.countDown();
            saturated.shutdown();
        }

        List<ILoggingEvent> warns = appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertEquals(1, warns.size());
        assertTrue(warns.get(0).getFormattedMessage().contains("41"));
        assertTrue(warns.get(0).getFormattedMessage().contains("queue_full"));
    }
}
