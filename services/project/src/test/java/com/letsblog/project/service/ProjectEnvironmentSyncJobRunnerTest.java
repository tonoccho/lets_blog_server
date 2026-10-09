package com.letsblog.project.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.project.client.BearerScope;
import com.letsblog.project.client.IdentityClient;
import com.letsblog.project.dto.SyncEnvironmentRequest;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 環境間同期ジョブの実行本体(issue #1697)。{@code @Async}はSpringプロキシ経由でしか効かないため、
 * メソッドを同期的に呼んで検証する(ManagedSiteProvisioningJobRunnerTest と同じ)。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("project-service: 環境間同期ジョブランナー(issue #1697)")
class ProjectEnvironmentSyncJobRunnerTest {

    private static final SyncEnvironmentRequest REQUEST =
            new SyncEnvironmentRequest("test", "local", List.of("db"));
    private static final ActorSnapshot ACTOR =
            new ActorSnapshot(5L, "sub-5", "a@example.com", "203.0.113.9", "ua", "Bearer abc", true);

    @Mock
    private ProjectEnvironmentSyncService syncService;
    @Mock
    private GenerationJobClient generationJobClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CurrentActorService currentActorService =
            new CurrentActorService(new MockHttpServletRequest(), mock(IdentityClient.class));
    private JobHeartbeatTracker tracker;
    private ProjectEnvironmentSyncJobRunner runner;

    @BeforeEach
    void setUp() {
        tracker = new JobHeartbeatTracker(generationJobClient, objectMapper);
        runner = new ProjectEnvironmentSyncJobRunner(
                syncService, currentActorService, generationJobClient, objectMapper, tracker);
    }

    private JsonNode lastPayload(String status) throws Exception {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).updateStatus(eq(21L), eq(status), payload.capture());
        return objectMapper.readTree(payload.getValue());
    }

    @Test
    @DisplayName("ジョブの完了で同期が実行され、syncing を running で通知してから done にする")
    void success() throws Exception {
        doAnswer(invocation -> {
            // ジョブのスレッドでも操作者(監査ログの参照先)が引ける。5分で切れる利用者のBearerは持ち込まない(#1723)。
            assertEquals(5L, currentActorService.getCurrentActorId());
            assertEquals("a@example.com", currentActorService.getCurrentActorEmail());
            assertNull(currentActorService.getAuthorizationHeader());
            assertNull(BearerScope.current());
            return null;
        }).when(syncService).sync(3L, "test", "local", List.of("db"));

        runner.run(21L, 3L, REQUEST, ACTOR);

        verify(syncService).sync(3L, "test", "local", List.of("db"));
        InOrder order = inOrder(generationJobClient);
        ArgumentCaptor<String> running = ArgumentCaptor.forClass(String.class);
        order.verify(generationJobClient).updateStatus(eq(21L), eq("running"), running.capture());
        assertEquals("syncing", objectMapper.readTree(running.getValue()).get("phase").asText());
        ArgumentCaptor<String> done = ArgumentCaptor.forClass(String.class);
        order.verify(generationJobClient).updateStatus(eq(21L), eq("done"), done.capture());
        JsonNode result = objectMapper.readTree(done.getValue());
        assertEquals("done", result.get("phase").asText());
        assertEquals(3L, result.get("projectId").asLong());
        assertEquals("test", result.get("from").asText());
        assertEquals("local", result.get("to").asText());
    }

    @Test
    @DisplayName("同期中のハートビートは syncing の段階を保って running を書く(#1724)")
    void heartbeatKeepsSyncingPhase() throws Exception {
        tracker.track(21L);
        doAnswer(invocation -> {
            tracker.heartbeat();
            return null;
        }).when(syncService).sync(any(), any(), any(), any());

        runner.run(21L, 3L, REQUEST, ACTOR);

        ArgumentCaptor<String> running = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient, org.mockito.Mockito.times(2)).updateStatus(eq(21L), eq("running"), running.capture());
        assertEquals("syncing", objectMapper.readTree(running.getAllValues().get(1)).get("phase").asText());
    }

    @Test
    @DisplayName("done を書いた後は追跡が外れ、ハートビートが running に戻さない(#1724)")
    void noHeartbeatAfterDone() {
        tracker.track(21L);

        runner.run(21L, 3L, REQUEST, ACTOR);
        tracker.heartbeat();

        assertFalse(tracker.isTracked(21L));
        // running は syncing の1回だけ(ハートビートの running は無い)
        verify(generationJobClient, org.mockito.Mockito.times(1)).updateStatus(eq(21L), eq("running"), any());
    }

    @Test
    @DisplayName("failed を書いた後も追跡が外れ、ハートビートが running に戻さない(#1724)")
    void noHeartbeatAfterFailed() {
        tracker.track(21L);
        doThrow(new IllegalStateException("x")).when(syncService).sync(any(), any(), any(), any());

        runner.run(21L, 3L, REQUEST, ACTOR);
        tracker.heartbeat();

        assertFalse(tracker.isTracked(21L));
        verify(generationJobClient, org.mockito.Mockito.times(1)).updateStatus(eq(21L), eq("running"), any());
        verify(generationJobClient).updateStatus(eq(21L), eq("failed"), any());
    }

    @Test
    @DisplayName("操作者とBearerの束縛は終わったら外れる")
    void unboundAfterRun() {
        runner.run(21L, 3L, REQUEST, ACTOR);

        assertNull(currentActorService.getCurrentActorKeycloakSub());
        assertNull(BearerScope.current());
    }

    @Test
    @DisplayName("同期の失敗は sync_failed の failed で、原因が読める")
    void syncFailed() throws Exception {
        doThrow(new ProvisioningException("環境同期に失敗しました: wp-cli", null))
                .when(syncService).sync(any(), any(), any(), any());

        runner.run(21L, 3L, REQUEST, ACTOR);

        JsonNode result = lastPayload("failed");
        assertEquals("sync_failed", result.get("errorType").asText());
        assertTrue(result.get("error").asText().contains("wp-cli"));
        verify(generationJobClient, never()).updateStatus(eq(21L), eq("done"), any());
    }

    @Test
    @DisplayName("プロジェクトが無い(ProjectNotFoundException)は project_not_found の failed")
    void projectNotFound() throws Exception {
        doThrow(new ProjectNotFoundException("id 3 のプロジェクトは登録されていません"))
                .when(syncService).sync(any(), any(), any(), any());

        runner.run(21L, 3L, REQUEST, ACTOR);

        assertEquals("project_not_found", lastPayload("failed").get("errorType").asText());
    }

    @Test
    @DisplayName("環境の指定不正(IllegalArgumentException)・サイト未登録は invalid_request の failed")
    void invalidRequest() throws Exception {
        doThrow(new IllegalArgumentException("本番環境は同期先に指定できません"))
                .when(syncService).sync(any(), any(), any(), any());

        runner.run(21L, 3L, REQUEST, ACTOR);

        assertEquals("invalid_request", lastPayload("failed").get("errorType").asText());
    }

    @Test
    @DisplayName("サイトが無い(SiteNotFoundException)も invalid_request")
    void siteNotFound() throws Exception {
        doThrow(new SiteNotFoundException("id 9 のサイトは登録されていません"))
                .when(syncService).sync(any(), any(), any(), any());

        runner.run(21L, 3L, REQUEST, ACTOR);

        assertEquals("invalid_request", lastPayload("failed").get("errorType").asText());
    }

    @Test
    @DisplayName("想定外の例外は unexpected_error の failed(メッセージが無くても読める文言になる)")
    void unexpected() throws Exception {
        doThrow(new IllegalStateException()).when(syncService).sync(any(), any(), any(), any());

        runner.run(21L, 3L, REQUEST, ACTOR);

        JsonNode result = lastPayload("failed");
        assertEquals("unexpected_error", result.get("errorType").asText());
        assertFalse(result.get("error").asText().isBlank());
    }

    @Test
    @DisplayName("リクエストの無いスレッドでも、取り置いた利用者のBearerはサービス間呼び出し用に束縛しない(#1723)")
    void userBearerNotBoundOnJobThread() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        doAnswer(invocation -> {
            seen.set(String.valueOf(BearerScope.current()) + "/" + currentActorService.getAuthorizationHeader());
            return null;
        }).when(syncService).sync(any(), any(), any(), any());

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
}
