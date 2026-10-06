package com.letsblog.content.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.client.GenerationJobBridgeException;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.common.client.GenerationJobSummary;
import com.letsblog.content.dto.GenerateCustomTagRequest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** カスタムタグ生成ジョブの受理側(issue #1409)。project-serviceのManagedSiteProvisioningJobStarterと同型。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("content-service: カスタムタグ生成ジョブの受理(issue #1409)")
class CustomTagGenerationJobStarterTest {

    private static final GenerateCustomTagRequest REQUEST =
            new GenerateCustomTagRequest("青いボタン", "blue-button", "説明", 4L);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 6, 1, 2, 3);

    @Mock
    private GenerationJobClient generationJobClient;
    @Mock
    private CustomTagGenerationJobRunner runner;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CustomTagGenerationJobStarter starter;

    @BeforeEach
    void setUp() {
        starter = new CustomTagGenerationJobStarter(
                generationJobClient, runner, adminAuthorizationService, objectMapper);
    }

    @Test
    @DisplayName("ジョブ種別は安定した名前 custom_tag_generation")
    void jobTypeIsStable() {
        assertEquals("custom_tag_generation", CustomTagGenerationJobStarter.JOB_TYPE);
    }

    @Test
    @DisplayName("管理者確認のあと、ユーザーのBearerでジョブを作り、ランナーを起動してジョブを返す")
    void startsJob() throws Exception {
        GenerationJobSummary job = new GenerationJobSummary(21L, "custom_tag_generation", "running", NOW, NOW);
        when(generationJobClient.create(eq("custom_tag_generation"), any(), eq("Bearer abc"))).thenReturn(job);

        GenerationJobSummary result = starter.start(REQUEST, "Bearer abc");

        assertSame(job, result);
        InOrder order = inOrder(adminAuthorizationService, generationJobClient, runner);
        order.verify(adminAuthorizationService).requireAdmin();
        order.verify(generationJobClient).create(eq("custom_tag_generation"), any(), eq("Bearer abc"));
        order.verify(runner).run(21L, REQUEST);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).create(eq("custom_tag_generation"), payload.capture(), eq("Bearer abc"));
        JsonNode json = objectMapper.readTree(payload.getValue());
        assertEquals("blue-button", json.get("tagName").asText());
        assertEquals("説明", json.get("description").asText());
        assertEquals(4L, json.get("projectId").asLong());
        assertEquals("青いボタン", json.get("prompt").asText());
    }

    @Test
    @DisplayName("管理者でなければジョブを作らず例外を伝える")
    void nonAdminIsRejected() {
        doThrow(new ForbiddenException("admin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> starter.start(REQUEST, "Bearer abc"));

        verify(generationJobClient, never()).create(any(), any(), any());
        verify(runner, never()).run(any(), any());
    }

    @Test
    @DisplayName("実行枠と待ち行列が満杯なら、ジョブを queue_full の failed にして返す")
    void queueFull() throws Exception {
        when(generationJobClient.create(any(), any(), any()))
                .thenReturn(new GenerationJobSummary(22L, "custom_tag_generation", "running", NOW, NOW));
        doThrow(new TaskRejectedException("full")).when(runner).run(any(), any());

        GenerationJobSummary result = starter.start(REQUEST, "Bearer abc");

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
        when(generationJobClient.create(any(), any(), any())).thenThrow(new GenerationJobBridgeException("down", null));

        assertThrows(GenerationJobBridgeException.class, () -> starter.start(REQUEST, "Bearer abc"));

        verify(runner, never()).run(any(), any());
    }
}
