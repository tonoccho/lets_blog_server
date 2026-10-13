package com.letsblog.publishing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.publishing.domain.BulkOperationLog;
import com.letsblog.publishing.domain.BulkOperationSourceType;
import com.letsblog.publishing.domain.BulkOperationStatus;
import com.letsblog.publishing.domain.BulkOperationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * アセット画像アップロードジョブの実行本体(issue #1764)。{@code @Async}はSpringプロキシ経由でしか効かないため、
 * メソッドを同期的に呼んで検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("publishing-service: アセット画像アップロードジョブランナー(issue #1764)")
class AssetImageUploadJobRunnerTest {

    private static final byte[] DATA = {1, 2, 3};

    @Mock
    private BulkManagementService bulkManagementService;
    @Mock
    private GenerationJobClient generationJobClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AssetImageUploadJobRunner runner;

    @BeforeEach
    void setUp() {
        runner = new AssetImageUploadJobRunner(bulkManagementService, generationJobClient, objectMapper);
    }

    private static BulkOperationLog log(String environment, BulkOperationStatus status, String error) {
        BulkOperationLog log = new BulkOperationLog();
        log.setProjectId(3L);
        log.setOperationType(BulkOperationType.MEDIA_UPLOAD);
        log.setSourceType(BulkOperationSourceType.SLUG);
        log.setValue("comfyui-5.png");
        log.setEnvironment(environment);
        log.setStatus(status);
        log.setLevel(status.toLogLevel());
        log.setErrorMessage(error);
        log.setCreatedAt(LocalDateTime.of(2026, 10, 13, 1, 2, 3));
        return log;
    }

    @Test
    @DisplayName("解決済みの長辺pxでアップロードし、環境ごとの結果(logs)を ENVIRONMENT_ORDER 順に載せて done にする。環境の失敗はFAILEDのログでジョブはdone")
    void success() throws Exception {
        when(bulkManagementService.uploadImageToAllEnvironments(3L, DATA, "comfyui-5.png", "image/png", 1300, 7L))
                .thenReturn(List.of(
                        log("local", BulkOperationStatus.SUCCESS, null),
                        log("test", BulkOperationStatus.FAILED, "画素数が上限を超えています"),
                        log("production", BulkOperationStatus.SUCCESS, null)));

        runner.run(31L, 3L, DATA, "comfyui-5.png", "image/png", 1300, 7L);

        InOrder order = inOrder(generationJobClient);
        ArgumentCaptor<String> running = ArgumentCaptor.forClass(String.class);
        order.verify(generationJobClient).updateStatus(eq(31L), eq("running"), running.capture());
        assertEquals("uploading", objectMapper.readTree(running.getValue()).get("phase").asText());
        ArgumentCaptor<String> done = ArgumentCaptor.forClass(String.class);
        order.verify(generationJobClient).updateStatus(eq(31L), eq("done"), done.capture());
        JsonNode result = objectMapper.readTree(done.getValue());
        assertEquals("done", result.get("phase").asText());
        assertEquals(3L, result.get("projectId").asLong());
        JsonNode logs = result.get("logs");
        assertEquals(3, logs.size());
        assertEquals("local", logs.get(0).get("environment").asText());
        assertEquals("test", logs.get(1).get("environment").asText());
        assertEquals("FAILED", logs.get(1).get("status").asText());
        assertEquals("画素数が上限を超えています", logs.get(1).get("errorMessage").asText());
        assertEquals("production", logs.get(2).get("environment").asText());
        assertEquals("MEDIA_UPLOAD", logs.get(0).get("operationType").asText());
        verify(generationJobClient, never()).updateStatus(eq(31L), eq("failed"), any());
    }

    @Test
    @DisplayName("プロジェクトが無ければ project_not_found の failed にする")
    void projectNotFound() throws Exception {
        when(bulkManagementService.uploadImageToAllEnvironments(any(), any(), any(), any(), anyInt(), any()))
                .thenThrow(new ProjectNotFoundException("project 3 not found"));

        runner.run(31L, 3L, DATA, "comfyui-5.png", "image/png", 1300, 7L);

        ArgumentCaptor<String> failed = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).updateStatus(eq(31L), eq("failed"), failed.capture());
        assertEquals("project_not_found", objectMapper.readTree(failed.getValue()).get("errorType").asText());
        verify(generationJobClient, never()).updateStatus(eq(31L), eq("done"), any());
    }

    @Test
    @DisplayName("想定外の例外は unexpected_error の failed にし、理由を載せる")
    void unexpectedFailure() throws Exception {
        when(bulkManagementService.uploadImageToAllEnvironments(any(), any(), any(), any(), anyInt(), any()))
                .thenThrow(new IllegalStateException("boom"));

        runner.run(31L, 3L, DATA, "comfyui-5.png", "image/png", 1300, 7L);

        ArgumentCaptor<String> failed = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).updateStatus(eq(31L), eq("failed"), failed.capture());
        JsonNode json = objectMapper.readTree(failed.getValue());
        assertEquals("unexpected_error", json.get("errorType").asText());
        assertTrue(json.get("error").asText().contains("boom"));
    }

    @Test
    @DisplayName("JSON化に失敗しても空のJSONで通知を続ける")
    void serializationFailureFallsBack() throws Exception {
        ObjectMapper broken = org.mockito.Mockito.mock(ObjectMapper.class);
        when(broken.writeValueAsString(any())).thenThrow(new RuntimeException("boom"));
        AssetImageUploadJobRunner brokenRunner = new AssetImageUploadJobRunner(bulkManagementService, generationJobClient, broken);
        when(bulkManagementService.uploadImageToAllEnvironments(any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(List.of());

        brokenRunner.run(31L, 3L, DATA, "comfyui-5.png", "image/png", 1300, 7L);

        verify(generationJobClient).updateStatus(31L, "running", "{}");
        verify(generationJobClient).updateStatus(31L, "done", "{}");
    }
}
