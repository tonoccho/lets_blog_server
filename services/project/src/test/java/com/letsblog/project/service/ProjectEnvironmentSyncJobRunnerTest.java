package com.letsblog.project.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.project.client.BearerScope;
import com.letsblog.project.domain.Project;
import com.letsblog.project.dto.SyncEnvironmentRequest;
import com.letsblog.project.repository.ProjectRepository;
import com.letsblog.project.testsupport.FakeHttpServer;
import com.letsblog.project.testsupport.ProjectWorld;
import com.letsblog.project.testsupport.ProjectWorld.JobUpdate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 環境間同期ジョブの実行本体(issue #1697)。{@code @Async}はSpringプロキシ経由でしか効かないため、
 * メソッドを同期的に呼んで検証する(ManagedSiteProvisioningJobRunnerTest と同じ)。
 *
 * <p>issue #1817: ProjectEnvironmentSyncService・各クライアント・GenerationJobClient・CurrentActorService は実物。
 * DB(ProjectRepository・SiteRepository)とHTTPの向こう(ai-service・provision-agent・identity-service)だけを差し替える。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("project-service: 環境間同期ジョブランナー(issue #1697)")
class ProjectEnvironmentSyncJobRunnerTest {

    private static final SyncEnvironmentRequest REQUEST =
            new SyncEnvironmentRequest("test", "local", List.of("db"));
    private static final ActorSnapshot ACTOR =
            new ActorSnapshot(5L, "sub-5", "a@example.com", "203.0.113.9", "ua", "Bearer abc", true);
    private static final String RECONCILE = "/api/internal/identity/project-users/3/sites/20/reconcile-roles";

    @Mock
    private ProjectRepository projectRepository;

    private final ProjectWorld world = new ProjectWorld();
    private JobHeartbeatTracker tracker;
    private ProjectEnvironmentSyncJobRunner runner;

    @BeforeEach
    void setUp() {
        tracker = new JobHeartbeatTracker(world.generationJobClient, world.objectMapper);
        ProjectEnvironmentSyncService syncService = new ProjectEnvironmentSyncService(projectRepository,
                world.siteRepository, world.siteService, world.wordPressSyncClient, world.cmsBridgeClient,
                world.identityBridgeClient, world.currentActorService);
        runner = new ProjectEnvironmentSyncJobRunner(
                syncService, world.currentActorService, world.generationJobClient, world.objectMapper, tracker);
        world.http.on("POST", "/sync", FakeHttpServer.Response.empty(200));
        world.http.on("POST", RECONCILE, FakeHttpServer.Response.empty(200));
        Project project = new Project();
        project.setId(3L);
        project.setTestSiteId(10L);
        project.setLocalSiteId(20L);
        lenient().when(projectRepository.findById(3L)).thenReturn(Optional.of(project));
        world.addManagedSite(10L, "from-slug", "wp_from");
        world.addManagedSite(20L, "to-slug", "wp_to");
    }

    @AfterEach
    void close() {
        BearerScope.call(null, () -> null);
        world.close();
    }

    private JsonNode lastPayload(String status) {
        List<JobUpdate> updates = world.jobUpdates(21L, status);
        assertEquals(1, updates.size());
        return updates.get(0).payload();
    }

    @Test
    @DisplayName("ジョブの完了で同期が実行され、syncing を running で通知してから done にする")
    void success() {
        AtomicReference<String> seen = new AtomicReference<>();
        // ジョブのスレッドでも操作者(監査ログの参照先)が引ける。5分で切れる利用者のBearerは持ち込まない(#1723)。
        when(projectRepository.findById(3L)).thenAnswer(inv -> {
            seen.set(world.currentActorService.getCurrentActorId() + "/" + world.currentActorService.getCurrentActorEmail()
                    + "/" + world.currentActorService.getAuthorizationHeader() + "/" + BearerScope.current());
            Project project = new Project();
            project.setId(3L);
            project.setTestSiteId(10L);
            project.setLocalSiteId(20L);
            return Optional.of(project);
        });

        runner.run(21L, 3L, REQUEST, ACTOR);

        assertEquals("5/a@example.com/null/null", seen.get());
        assertEquals(1, world.http.requests("POST", "/sync").size());
        assertEquals(List.of("running", "done"), world.jobUpdates(21L).stream().map(JobUpdate::status).toList());
        assertEquals("syncing", world.jobUpdates(21L).get(0).payload().get("phase").asText());
        JsonNode result = lastPayload("done");
        assertEquals("done", result.get("phase").asText());
        assertEquals(3L, result.get("projectId").asLong());
        assertEquals("test", result.get("from").asText());
        assertEquals("local", result.get("to").asText());
        assertEquals("db", result.get("targets").get(0).asText());
        // ロール再整合も、利用者のBearerではなくサービス自身のトークンで依頼する。
        assertEquals("Bearer " + ProjectWorld.SERVICE_TOKEN, world.http.requests("POST", RECONCILE).get(0).header("Authorization"));
    }

    @Test
    @DisplayName("同期中のハートビートは syncing の段階を保って running を書く(#1724)")
    void heartbeatKeepsSyncingPhase() {
        tracker.track(21L);
        when(projectRepository.findById(3L)).thenAnswer(inv -> {
            tracker.heartbeat();
            Project project = new Project();
            project.setId(3L);
            project.setTestSiteId(10L);
            project.setLocalSiteId(20L);
            return Optional.of(project);
        });

        runner.run(21L, 3L, REQUEST, ACTOR);

        List<JobUpdate> running = world.jobUpdates(21L, "running");
        assertEquals(2, running.size());
        assertEquals("syncing", running.get(1).payload().get("phase").asText());
    }

    @Test
    @DisplayName("done を書いた後は追跡が外れ、ハートビートが running に戻さない(#1724)")
    void noHeartbeatAfterDone() {
        tracker.track(21L);

        runner.run(21L, 3L, REQUEST, ACTOR);
        tracker.heartbeat();

        assertFalse(tracker.isTracked(21L));
        // running は syncing の1回だけ(ハートビートの running は無い)
        assertEquals(1, world.jobUpdates(21L, "running").size());
    }

    @Test
    @DisplayName("failed を書いた後も追跡が外れ、ハートビートが running に戻さない(#1724)")
    void noHeartbeatAfterFailed() {
        tracker.track(21L);
        when(projectRepository.findById(3L)).thenThrow(new IllegalStateException("x"));

        runner.run(21L, 3L, REQUEST, ACTOR);
        tracker.heartbeat();

        assertFalse(tracker.isTracked(21L));
        assertEquals(1, world.jobUpdates(21L, "running").size());
        assertEquals(1, world.jobUpdates(21L, "failed").size());
    }

    @Test
    @DisplayName("操作者とBearerの束縛は終わったら外れる")
    void unboundAfterRun() {
        runner.run(21L, 3L, REQUEST, ACTOR);

        assertNull(world.currentActorService.getCurrentActorKeycloakSub());
        assertNull(BearerScope.current());
    }

    @Test
    @DisplayName("同期の失敗は sync_failed の failed で、原因が読める")
    void syncFailed() {
        world.http.status("POST", "/sync", 500, "wp-cli");

        runner.run(21L, 3L, REQUEST, ACTOR);

        JsonNode result = lastPayload("failed");
        assertEquals("sync_failed", result.get("errorType").asText());
        assertTrue(result.get("error").asText().contains("wp-cli"));
        assertTrue(world.jobUpdates(21L, "done").isEmpty());
    }

    @Test
    @DisplayName("プロジェクトが無い(ProjectNotFoundException)は project_not_found の failed")
    void projectNotFound() {
        when(projectRepository.findById(3L)).thenReturn(Optional.empty());

        runner.run(21L, 3L, REQUEST, ACTOR);

        assertEquals("project_not_found", lastPayload("failed").get("errorType").asText());
    }

    @Test
    @DisplayName("環境の指定不正(IllegalArgumentException)・サイト未登録は invalid_request の failed")
    void invalidRequest() {
        runner.run(21L, 3L, new SyncEnvironmentRequest("test", "production", List.of("db")), ACTOR);

        assertEquals("invalid_request", lastPayload("failed").get("errorType").asText());
    }

    @Test
    @DisplayName("サイトが無い(SiteNotFoundException)も invalid_request")
    void siteNotFound() {
        Project project = new Project();
        project.setId(3L);
        project.setTestSiteId(99L);
        project.setLocalSiteId(20L);
        when(projectRepository.findById(3L)).thenReturn(Optional.of(project));

        runner.run(21L, 3L, REQUEST, ACTOR);

        assertEquals("invalid_request", lastPayload("failed").get("errorType").asText());
    }

    @Test
    @DisplayName("想定外の例外は unexpected_error の failed(メッセージが無くても読める文言になる)")
    void unexpected() {
        when(projectRepository.findById(3L)).thenThrow(new IllegalStateException());

        runner.run(21L, 3L, REQUEST, ACTOR);

        JsonNode result = lastPayload("failed");
        assertEquals("unexpected_error", result.get("errorType").asText());
        assertFalse(result.get("error").asText().isBlank());
    }

    @Test
    @DisplayName("リクエストの無いスレッドでも、取り置いた利用者のBearerはサービス間呼び出し用に束縛しない(#1723)")
    void userBearerNotBoundOnJobThread() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        when(projectRepository.findById(3L)).thenAnswer(inv -> {
            seen.set(BearerScope.current() + "/" + world.currentActorService.getAuthorizationHeader());
            return Optional.empty();
        });

        Thread t = new Thread(() -> runner.run(21L, 3L, REQUEST, ACTOR));
        t.start();
        t.join();

        assertEquals("null/null", seen.get());
    }

    @Test
    @DisplayName("Bean 名は AsyncJobConfig の environmentSyncExecutor と一致する")
    void asyncQualifier() throws Exception {
        String qualifier = ProjectEnvironmentSyncJobRunner.class
                .getMethod("run", Long.class, Long.class, SyncEnvironmentRequest.class, ActorSnapshot.class)
                .getAnnotation(org.springframework.scheduling.annotation.Async.class).value();
        assertEquals("environmentSyncExecutor", qualifier);
    }

    /** ジョブ1件の完了行(issue #1731)だけを集める。 */
    private static List<ILoggingEvent> completionLines(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(ProjectEnvironmentSyncJobRunner.class);
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
        List<ILoggingEvent> lines = completionLines(() -> runner.run(21L, 3L, REQUEST, ACTOR));

        assertEquals(1, lines.size(), lines.toString());
        assertEquals(Level.INFO, lines.get(0).getLevel());
        assertTrue(lines.get(0).getFormattedMessage()
                .matches("job completed: kind=environment_sync job_id=21 duration_ms=\\d+ outcome=success"),
                lines.get(0).getFormattedMessage());
    }

    @Test
    void 完了行_失敗はWARN以上で例外クラス名をoutcomeに出す() {
        world.fail("POST", "/sync", 500);
        List<ILoggingEvent> lines = completionLines(() -> runner.run(21L, 3L, REQUEST, ACTOR));

        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).getLevel().isGreaterOrEqual(Level.WARN));
        assertTrue(lines.get(0).getFormattedMessage()
                .matches("job completed: kind=environment_sync job_id=21 duration_ms=\\d+ outcome=ProvisioningException"),
                lines.get(0).getFormattedMessage());
    }
}
