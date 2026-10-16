package com.letsblog.project.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.project.client.BearerScope;
import com.letsblog.project.domain.Site;
import com.letsblog.project.dto.CreateManagedWordPressSiteRequest;
import com.letsblog.project.testsupport.FakeHttpServer;
import com.letsblog.project.testsupport.ProjectWorld;
import com.letsblog.project.testsupport.ProjectWorld.JobUpdate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * サイト自動構築ジョブの実行本体(issue #1479)。{@code @Async}はSpringプロキシ経由でしか効かないため、
 * メソッドを同期的に呼んで検証する(ImageGenerationJobRunnerTestと同じ)。
 *
 * <p>issue #1817: WordPressSiteProvisioningService・SiteService・GenerationJobClient・CurrentActorService は
 * 実物。DB(SiteRepository)とHTTPの向こう(ai-service・provision-agent・publishing-service)だけを差し替える。
 */
@DisplayName("project-service: サイト自動構築ジョブランナー(issue #1479)")
class ManagedSiteProvisioningJobRunnerTest {

    private static final CreateManagedWordPressSiteRequest REQUEST = new CreateManagedWordPressSiteRequest(
            "Name", "my-site", "Title", "admin", "admin@example.com", "pw", "ja", null);
    private static final ActorSnapshot ACTOR =
            new ActorSnapshot(5L, "sub-5", "a@example.com", "203.0.113.9", "ua", "Bearer abc", true);

    private final ProjectWorld world = new ProjectWorld();
    private JobHeartbeatTracker tracker;
    private ManagedSiteProvisioningJobRunner runner;

    @BeforeEach
    void setUp() {
        tracker = new JobHeartbeatTracker(world.generationJobClient, world.objectMapper);
        WordPressSiteProvisioningService provisioningService = new WordPressSiteProvisioningService(
                world.wordPressProvisioningClient, world.wordPressSyncClient, world.siteService,
                world.siteRepository, world.domainEventPublisher);
        runner = new ManagedSiteProvisioningJobRunner(provisioningService, world.currentActorService,
                world.generationJobClient, world.objectMapper, tracker);
        world.registrationSucceeds();
        world.respond("POST", "/provision", Map.of("url", "https://localhost/sites/my-site", "adminUser", "admin"));
        world.http.on("POST", "/deprovision", FakeHttpServer.Response.empty(200));
        world.http.on("POST", "/sync", FakeHttpServer.Response.empty(200));
    }

    @AfterEach
    void close() {
        world.close();
    }

    private Site registeredSite() {
        Site site = new Site();
        site.setId(7L);
        site.setName("Name");
        site.setSiteKey("my-site");
        site.setBaseUrl("https://localhost/sites/my-site");
        when(world.siteRepository.findBySiteKey("my-site")).thenReturn(Optional.of(site));
        return site;
    }

    private JsonNode lastPayload(String status) {
        List<JobUpdate> updates = world.jobUpdates(11L, status);
        assertEquals(1, updates.size());
        return updates.get(0).payload();
    }

    @Test
    @DisplayName("成功すると provisioning → registering の進捗を running で通知し、done の結果にサイトIDを載せる")
    void success() {
        registeredSite();
        // ジョブのスレッドでも操作者(監査ログ・著者解決の参照先)が引ける。
        AtomicReference<String> sub = new AtomicReference<>();
        when(world.siteRepository.existsBySiteKey(anyString())).thenAnswer(inv -> {
            sub.set(world.currentActorService.getCurrentActorKeycloakSub() + "/" + world.currentActorService.getCurrentActorId());
            return false;
        });

        runner.run(11L, REQUEST, ACTOR);

        assertEquals("sub-5/5", sub.get());
        List<JobUpdate> running = world.jobUpdates(11L, "running");
        assertEquals("provisioning", running.get(0).payload().get("phase").asText());
        assertEquals("registering", running.get(1).payload().get("phase").asText());
        assertEquals(2, running.size());
        assertEquals(List.of("running", "running", "done"),
                world.jobUpdates(11L).stream().map(JobUpdate::status).toList());
        JsonNode result = lastPayload("done");
        assertEquals("done", result.get("phase").asText());
        assertEquals(7L, result.get("siteId").asLong());
        assertEquals("my-site", result.get("siteKey").asText());
        assertFalse(world.jobUpdates(11L).toString().contains("pw\""));
        // サイト登録の著者は、スナップショットの操作者のメールになる。
        assertEquals("a@example.com",
                world.bodiesOf("POST", "/api/internal/project/cms/provision").get(0).get("actorEmail").asText());
    }

    @Test
    @DisplayName("段階の通知はハートビートが保つ最新の段階にもなる(#1724)")
    void heartbeatKeepsLatestPhase() {
        tracker.track(11L);
        registeredSite();
        // 登録の段階(registering)にいるあいだにハートビートが打たれる。
        when(world.siteRepository.findBySiteKey("my-site")).thenAnswer(inv -> {
            tracker.heartbeat();
            Site site = new Site();
            site.setId(7L);
            site.setSiteKey("my-site");
            return Optional.of(site);
        });

        runner.run(11L, REQUEST, ACTOR);

        List<JobUpdate> running = world.jobUpdates(11L, "running");
        assertEquals(3, running.size());
        assertEquals("registering", running.get(2).payload().get("phase").asText());
    }

    @Test
    @DisplayName("done を書いた後は追跡が外れ、ハートビートが running に戻さない(#1724)")
    void noHeartbeatAfterDone() {
        tracker.track(11L);
        registeredSite();

        runner.run(11L, REQUEST, ACTOR);
        int before = world.jobUpdates(11L).size();
        tracker.heartbeat();

        assertFalse(tracker.isTracked(11L));
        assertEquals(before, world.jobUpdates(11L).size());
        assertEquals(1, world.jobUpdates(11L, "done").size());
    }

    @Test
    @DisplayName("failed を書いた後も追跡が外れ、ハートビートが running に戻さない(#1724)")
    void noHeartbeatAfterFailed() {
        tracker.track(11L);
        when(world.siteRepository.existsBySiteKey(anyString())).thenThrow(new IllegalStateException("x"));

        runner.run(11L, REQUEST, ACTOR);
        tracker.heartbeat();

        assertFalse(tracker.isTracked(11L));
        assertEquals(List.of("failed"), world.jobUpdates(11L).stream().map(JobUpdate::status).toList());
    }

    @Test
    @DisplayName("操作者の束縛は終わったら外れる")
    void actorUnboundAfterRun() {
        registeredSite();

        runner.run(11L, REQUEST, ACTOR);

        assertNull(world.currentActorService.getCurrentActorKeycloakSub());
    }

    @Test
    @DisplayName("siteKey 重複は duplicate_site_key の failed")
    void duplicate() {
        when(world.siteRepository.existsBySiteKey("my-site")).thenReturn(true);

        runner.run(11L, REQUEST, ACTOR);

        JsonNode result = lastPayload("failed");
        assertEquals("duplicate_site_key", result.get("errorType").asText());
        assertTrue(result.get("error").asText().contains("my-site"));
    }

    @Test
    @DisplayName("実体が既にある(409)は already_provisioned の failed")
    void alreadyProvisioned() {
        world.fail("POST", "/provision", 409);

        runner.run(11L, REQUEST, ACTOR);

        assertEquals("already_provisioned", lastPayload("failed").get("errorType").asText());
    }

    @Test
    @DisplayName("構築失敗は provisioning_failed の failed で、原因が読める")
    void provisioningFailed() {
        world.http.status("POST", "/provision", 500, "wp-cli");

        runner.run(11L, REQUEST, ACTOR);

        JsonNode result = lastPayload("failed");
        assertEquals("provisioning_failed", result.get("errorType").asText());
        assertTrue(result.get("error").asText().contains("wp-cli"));
        assertTrue(world.jobUpdates(11L, "done").isEmpty());
    }

    @Test
    @DisplayName("テンプレート不正(IllegalArgumentException)・未登録は invalid_request の failed")
    void invalidRequest() {
        registeredSite();
        world.addSite(3L, "テンプレート");

        runner.run(11L, new CreateManagedWordPressSiteRequest(
                "Name", "my-site", "Title", "admin", "admin@example.com", "pw", "ja", 3L), ACTOR);

        JsonNode result = lastPayload("failed");
        assertEquals("invalid_request", result.get("errorType").asText());
        assertTrue(result.get("error").asText().contains("自動構築サイト"));
    }

    @Test
    @DisplayName("テンプレートサイトが無い(SiteNotFoundException)も invalid_request")
    void templateNotFound() {
        runner.run(11L, new CreateManagedWordPressSiteRequest(
                "Name", "my-site", "Title", "admin", "admin@example.com", "pw", "ja", 3L), ACTOR);

        assertEquals("invalid_request", lastPayload("failed").get("errorType").asText());
    }

    @Test
    @DisplayName("想定外の例外は unexpected_error の failed(メッセージが無くても読める文言になる)")
    void unexpected() {
        when(world.siteRepository.existsBySiteKey(anyString())).thenThrow(new IllegalStateException());

        runner.run(11L, REQUEST, ACTOR);

        JsonNode result = lastPayload("failed");
        assertEquals("unexpected_error", result.get("errorType").asText());
        assertFalse(result.get("error").asText().isBlank());
    }

    @Test
    @DisplayName("ジョブのスレッドでは、取り置いた利用者のBearerをサービス間呼び出し用に束縛しない。操作者は引ける(#1723)")
    void userBearerNotBoundOnJobThread() throws Exception {
        registeredSite();
        AtomicReference<String> seen = new AtomicReference<>();
        when(world.siteRepository.existsBySiteKey(anyString())).thenAnswer(inv -> {
            seen.set(BearerScope.current() + "/" + world.currentActorService.getAuthorizationHeader() + "/"
                    + world.currentActorService.getCurrentActorEmail());
            return false;
        });

        Thread t = new Thread(() -> runner.run(11L, REQUEST, ACTOR));
        t.start();
        t.join();

        assertEquals("null/null/a@example.com", seen.get());
        assertNull(BearerScope.current());
        // 利用者のBearerではなく、サービス自身のトークンで publishing-service を呼ぶ。
        assertEquals("Bearer " + ProjectWorld.SERVICE_TOKEN,
                world.http.requests("POST", "/api/internal/project/cms/provision").get(0).header("Authorization"));
    }

    /** ジョブ1件の完了行(issue #1731)だけを集める。 */
    private static List<ILoggingEvent> completionLines(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(ManagedSiteProvisioningJobRunner.class);
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
        registeredSite();
        List<ILoggingEvent> lines = completionLines(() -> runner.run(11L, REQUEST, ACTOR));

        assertEquals(1, lines.size(), lines.toString());
        assertEquals(Level.INFO, lines.get(0).getLevel());
        assertTrue(lines.get(0).getFormattedMessage()
                .matches("job completed: kind=site_provisioning job_id=11 duration_ms=\\d+ outcome=success"),
                lines.get(0).getFormattedMessage());
    }

    @Test
    void 完了行_失敗はWARN以上で例外クラス名をoutcomeに出す() {
        world.fail("POST", "/provision", 500);
        List<ILoggingEvent> lines = completionLines(() -> runner.run(11L, REQUEST, ACTOR));

        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).getLevel().isGreaterOrEqual(Level.WARN));
        assertTrue(lines.get(0).getFormattedMessage()
                .matches("job completed: kind=site_provisioning job_id=11 duration_ms=\\d+ outcome=ProvisioningException"),
                lines.get(0).getFormattedMessage());
    }
}
