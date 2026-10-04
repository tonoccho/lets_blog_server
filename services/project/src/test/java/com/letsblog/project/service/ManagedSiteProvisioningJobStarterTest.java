package com.letsblog.project.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.common.client.GenerationJobSummary;
import com.letsblog.project.dto.CreateManagedWordPressSiteRequest;
import java.time.LocalDateTime;
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

/** サイト自動構築ジョブの受理側(issue #1479)。ImageGenerationJobStarterTest相当。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("project-service: サイト自動構築ジョブの受理(issue #1479)")
class ManagedSiteProvisioningJobStarterTest {

    private static final CreateManagedWordPressSiteRequest REQUEST = new CreateManagedWordPressSiteRequest(
            "Name", "my-site", "Title", "admin", "admin@example.com", "S3cret-pw", "ja", 4L);
    private static final ActorSnapshot ACTOR =
            new ActorSnapshot(5L, "sub-5", "a@example.com", "203.0.113.9", "ua", "Bearer abc", true);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 1, 2, 3);

    @Mock
    private GenerationJobClient generationJobClient;
    @Mock
    private ManagedSiteProvisioningJobRunner runner;
    @Mock
    private CurrentActorService currentActorService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ManagedSiteProvisioningJobStarter starter;

    @BeforeEach
    void setUp() {
        starter = new ManagedSiteProvisioningJobStarter(generationJobClient, runner, currentActorService, objectMapper);
    }

    @Test
    @DisplayName("ジョブ種別は安定した非AIの名前 site_provisioning")
    void jobTypeIsStable() {
        assertEquals("site_provisioning", ManagedSiteProvisioningJobStarter.JOB_TYPE);
    }

    @Test
    @DisplayName("リクエストスレッドで操作者を取り、ユーザーのBearerでジョブを作り、同じ操作者をランナーへ渡す")
    void startsJobWithActorResolvedInRequestThread() throws Exception {
        when(currentActorService.snapshot()).thenReturn(ACTOR);
        GenerationJobSummary job = new GenerationJobSummary(11L, "site_provisioning", "running", NOW, NOW);
        when(generationJobClient.create(eq("site_provisioning"), any(), eq("Bearer abc"))).thenReturn(job);

        GenerationJobSummary result = starter.start(REQUEST);

        assertSame(job, result);
        verify(runner).run(11L, REQUEST, ACTOR);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).create(eq("site_provisioning"), payload.capture(), eq("Bearer abc"));
        JsonNode json = objectMapper.readTree(payload.getValue());
        assertEquals("my-site", json.get("siteKey").asText());
        assertEquals("Name", json.get("name").asText());
        assertEquals(4L, json.get("templateSiteId").asLong());
        // 管理者パスワードとメールはジョブの要求内容(誰でも読める)へ残さない。
        assertFalse(payload.getValue().contains("S3cret-pw"));
        assertFalse(json.has("adminPassword"));
        assertFalse(json.has("adminEmail"));
    }

    @Test
    @DisplayName("実行枠と待ち行列が満杯なら、ジョブを queue_full の failed にして返す")
    void queueFull() throws Exception {
        when(currentActorService.snapshot()).thenReturn(ACTOR);
        when(generationJobClient.create(any(), any(), any()))
                .thenReturn(new GenerationJobSummary(12L, "site_provisioning", "running", NOW, NOW));
        doThrow(new TaskRejectedException("full")).when(runner).run(any(), any(), any());

        GenerationJobSummary result = starter.start(REQUEST);

        assertEquals("failed", result.status());
        assertEquals(12L, result.id());
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).updateStatus(eq(12L), eq("failed"), payload.capture());
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

        assertThrows(com.letsblog.common.client.GenerationJobBridgeException.class, () -> starter.start(REQUEST));

        verify(runner, never()).run(any(), any(), any());
    }
}
