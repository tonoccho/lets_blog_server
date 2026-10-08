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
    private ProjectEnvironmentSyncJobRunner runner;

    @BeforeEach
    void setUp() {
        runner = new ProjectEnvironmentSyncJobRunner(syncService, currentActorService, generationJobClient, objectMapper);
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
            // ジョブのスレッドでも操作者(監査ログの参照先)とBearerが引ける。
            assertEquals(5L, currentActorService.getCurrentActorId());
            assertEquals("Bearer abc", currentActorService.getAuthorizationHeader());
            assertEquals("Bearer abc", BearerScope.current());
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
    @DisplayName("リクエストの無いスレッドでも、スナップショットのAuthorizationがBearerとして引ける")
    void bearerBoundOnJobThread() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        doAnswer(invocation -> {
            seen.set(BearerScope.current());
            return null;
        }).when(syncService).sync(any(), any(), any(), any());

        Thread t = new Thread(() -> runner.run(21L, 3L, REQUEST, ACTOR));
        t.start();
        t.join();

        assertEquals("Bearer abc", seen.get());
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
