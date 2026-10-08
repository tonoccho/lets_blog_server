package com.letsblog.project.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.common.client.GenerationJobSummary;
import com.letsblog.project.dto.SyncEnvironmentRequest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 環境間同期ジョブの受理側(issue #1697)。ManagedSiteProvisioningJobStarterTest と同型。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("project-service: 環境間同期ジョブの受理(issue #1697)")
class ProjectEnvironmentSyncJobStarterTest {

    private static final SyncEnvironmentRequest REQUEST =
            new SyncEnvironmentRequest("test", "local", List.of("db", "media"));
    private static final ActorSnapshot ACTOR =
            new ActorSnapshot(5L, "sub-5", "a@example.com", "203.0.113.9", "ua", "Bearer abc", true);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 9, 1, 2, 3);

    @Mock
    private GenerationJobClient generationJobClient;
    @Mock
    private ProjectEnvironmentSyncJobRunner runner;
    @Mock
    private CurrentActorService currentActorService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ProjectEnvironmentSyncJobStarter starter;

    @BeforeEach
    void setUp() {
        starter = new ProjectEnvironmentSyncJobStarter(generationJobClient, runner, currentActorService, objectMapper);
    }

    @Test
    @DisplayName("ジョブ種別は安定した非AIの名前 environment_sync")
    void jobTypeIsStable() {
        assertEquals("environment_sync", ProjectEnvironmentSyncJobStarter.JOB_TYPE);
    }

    @Test
    @DisplayName("リクエストスレッドで操作者を取り、ユーザーのBearerでジョブを作り、同じ操作者をランナーへ渡す")
    void startsJobWithActorResolvedInRequestThread() throws Exception {
        when(currentActorService.snapshot()).thenReturn(ACTOR);
        GenerationJobSummary job = new GenerationJobSummary(21L, "environment_sync", "running", NOW, NOW);
        when(generationJobClient.create(eq("environment_sync"), any(), eq("Bearer abc"))).thenReturn(job);

        GenerationJobSummary result = starter.start(3L, REQUEST);

        assertSame(job, result);
        verify(runner).run(21L, 3L, REQUEST, ACTOR);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).create(eq("environment_sync"), payload.capture(), eq("Bearer abc"));
        JsonNode json = objectMapper.readTree(payload.getValue());
        // キューUIが結果の遷移先(プロジェクトの設定タブ)を決めるのに projectId を読む。
        assertEquals(3L, json.get("projectId").asLong());
        assertEquals("test", json.get("from").asText());
        assertEquals("local", json.get("to").asText());
        assertEquals("db", json.get("targets").get(0).asText());
        assertEquals("media", json.get("targets").get(1).asText());
    }

    @Test
    @DisplayName("実行枠と待ち行列が満杯なら、ジョブを queue_full の failed にして返す")
    void queueFull() throws Exception {
        when(currentActorService.snapshot()).thenReturn(ACTOR);
        when(generationJobClient.create(any(), any(), any()))
                .thenReturn(new GenerationJobSummary(22L, "environment_sync", "running", NOW, NOW));
        doThrow(new TaskRejectedException("full")).when(runner).run(any(), any(), any(), any());

        GenerationJobSummary result = starter.start(3L, REQUEST);

        assertEquals("failed", result.status());
        assertEquals(22L, result.id());
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).updateStatus(eq(22L), eq("failed"), payload.capture());
        JsonNode json = objectMapper.readTree(payload.getValue());
        assertEquals("queue_full", json.get("errorType").asText());
        assertFalse(json.get("error").asText().isBlank());
    }

    @Test
    @DisplayName("ジョブを作れなければ(ai-service 不達)ランナーを起動せず例外を伝える")
    void createFailurePropagates() {
        when(currentActorService.snapshot()).thenReturn(ACTOR);
        when(generationJobClient.create(any(), any(), any()))
                .thenThrow(new com.letsblog.common.client.GenerationJobBridgeException("down", null));

        assertThrows(com.letsblog.common.client.GenerationJobBridgeException.class, () -> starter.start(3L, REQUEST));

        verify(runner, never()).run(any(), any(), any(), any());
    }
}
