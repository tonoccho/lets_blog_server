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

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 全環境インストールジョブの実行本体(issue #1702)。{@code @Async}はSpringプロキシ経由でしか効かないため、
 * メソッドを同期的に呼んで検証する(project-serviceの ProjectEnvironmentSyncJobRunnerTest と同じ)。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("publishing-service: 全環境インストールジョブランナー(issue #1702)")
class BulkInstallJobRunnerTest {

    private static final BulkUploadStorageService.StoredZip STORED =
            new BulkUploadStorageService.StoredZip("3/abc.zip", "abc", "my-plugin.zip");

    @Mock
    private BulkManagementService bulkManagementService;
    @Mock
    private BulkUploadStorageService storageService;
    @Mock
    private GenerationJobClient generationJobClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private BulkInstallJobRunner runner;

    @BeforeEach
    void setUp() {
        runner = new BulkInstallJobRunner(bulkManagementService, storageService, generationJobClient, objectMapper);
    }

    private static BulkOperationLog log(String environment, BulkOperationStatus status, String error) {
        BulkOperationLog log = new BulkOperationLog();
        log.setProjectId(3L);
        log.setOperationType(BulkOperationType.PLUGIN_INSTALL);
        log.setSourceType(BulkOperationSourceType.SLUG);
        log.setValue("akismet");
        log.setEnvironment(environment);
        log.setStatus(status);
        log.setLevel(status.toLogLevel());
        log.setErrorMessage(error);
        log.setCreatedAt(LocalDateTime.of(2026, 10, 13, 1, 2, 3));
        return log;
    }

    private JsonNode terminalPayload(String status) throws Exception {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).updateStatus(eq(21L), eq(status), payload.capture());
        return objectMapper.readTree(payload.getValue());
    }

    @Test
    @DisplayName("slug: installing を running で通知してから、環境ごとの結果(logs)を載せて done にする")
    void slugSuccess() throws Exception {
        when(bulkManagementService.applyToAllEnvironments(3L, BulkOperationType.PLUGIN_INSTALL, "akismet", 5L))
                .thenReturn(List.of(
                        log("local", BulkOperationStatus.SUCCESS, null),
                        log("test", BulkOperationStatus.FAILED, "wp-cli: 失敗")));

        runner.runSlugInstall(21L, 3L, BulkOperationType.PLUGIN_INSTALL, "akismet", 5L);

        InOrder order = inOrder(generationJobClient);
        ArgumentCaptor<String> running = ArgumentCaptor.forClass(String.class);
        order.verify(generationJobClient).updateStatus(eq(21L), eq("running"), running.capture());
        assertEquals("installing", objectMapper.readTree(running.getValue()).get("phase").asText());
        ArgumentCaptor<String> done = ArgumentCaptor.forClass(String.class);
        order.verify(generationJobClient).updateStatus(eq(21L), eq("done"), done.capture());
        JsonNode result = objectMapper.readTree(done.getValue());
        assertEquals("done", result.get("phase").asText());
        assertEquals(3L, result.get("projectId").asLong());
        JsonNode logs = result.get("logs");
        assertEquals(2, logs.size());
        // 環境の順(呼び出し元が返した順=ENVIRONMENT_ORDER)を保つ。環境ごとの失敗は FAILED の log で、ジョブは done。
        assertEquals("local", logs.get(0).get("environment").asText());
        assertEquals("SUCCESS", logs.get(0).get("status").asText());
        assertEquals("test", logs.get(1).get("environment").asText());
        assertEquals("FAILED", logs.get(1).get("status").asText());
        assertEquals("wp-cli: 失敗", logs.get(1).get("errorMessage").asText());
        // BulkOperationLogResponse と同じ形
        assertEquals("PLUGIN_INSTALL", logs.get(0).get("operationType").asText());
        assertEquals("SLUG", logs.get(0).get("sourceType").asText());
        assertEquals("akismet", logs.get(0).get("value").asText());
        assertEquals("2026-10-13T01:02:03Z", logs.get(0).get("createdAt").asText());
        verify(generationJobClient, never()).updateStatus(eq(21L), eq("failed"), any());
    }

    @Test
    @DisplayName("zip: 保存先から読み、保存済みのバイト列を各環境へ適用して done にする")
    void zipSuccess() throws Exception {
        byte[] bytes = {1, 2, 3};
        when(storageService.load("3/abc.zip")).thenReturn(bytes);
        BulkOperationLog zipLog = log("local", BulkOperationStatus.SUCCESS, null);
        zipLog.setSourceType(BulkOperationSourceType.ZIP);
        zipLog.setOriginalFilename("my-plugin.zip");
        when(bulkManagementService.applyStoredZipToAllEnvironments(3L, BulkOperationType.PLUGIN_INSTALL, bytes, STORED, 5L))
                .thenReturn(List.of(zipLog));

        runner.runZipInstall(21L, 3L, BulkOperationType.PLUGIN_INSTALL, STORED, 5L);

        JsonNode result = terminalPayload("done");
        assertEquals("ZIP", result.get("logs").get(0).get("sourceType").asText());
        assertEquals("my-plugin.zip", result.get("logs").get(0).get("originalFilename").asText());
    }

    @Test
    @DisplayName("zip: 保存済みファイルが読めなければ unexpected_error の failed")
    void zipLoadFailure() throws Exception {
        when(storageService.load(any())).thenThrow(new IOException("保存済みファイルが見つかりません"));

        runner.runZipInstall(21L, 3L, BulkOperationType.PLUGIN_INSTALL, STORED, 5L);

        JsonNode result = terminalPayload("failed");
        assertEquals("unexpected_error", result.get("errorType").asText());
        assertTrue(result.get("error").asText().contains("保存済みファイル"));
    }

    @Test
    @DisplayName("プロジェクトが無い(ProjectNotFoundException)は project_not_found の failed")
    void projectNotFound() throws Exception {
        when(bulkManagementService.applyToAllEnvironments(any(), any(), any(), any()))
                .thenThrow(new ProjectNotFoundException("id 3 のプロジェクトは登録されていません"));

        runner.runSlugInstall(21L, 3L, BulkOperationType.PLUGIN_INSTALL, "akismet", 5L);

        assertEquals("project_not_found", terminalPayload("failed").get("errorType").asText());
        verify(generationJobClient, never()).updateStatus(eq(21L), eq("done"), any());
    }

    @Test
    @DisplayName("入力の不正(IllegalArgumentException)は invalid_request の failed")
    void invalidRequest() throws Exception {
        when(bulkManagementService.applyToAllEnvironments(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("slugを入力してください"));

        runner.runSlugInstall(21L, 3L, BulkOperationType.PLUGIN_INSTALL, "akismet", 5L);

        assertEquals("invalid_request", terminalPayload("failed").get("errorType").asText());
    }

    @Test
    @DisplayName("想定外の例外は unexpected_error の failed(メッセージが無くても読める文言になる)")
    void unexpected() throws Exception {
        when(bulkManagementService.applyToAllEnvironments(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException());

        runner.runSlugInstall(21L, 3L, BulkOperationType.PLUGIN_INSTALL, "akismet", 5L);

        JsonNode result = terminalPayload("failed");
        assertEquals("unexpected_error", result.get("errorType").asText());
        assertFalse(result.get("error").asText().isBlank());
    }

    @Test
    @DisplayName("ログの optional な項目(null)も、そのまま null として載る")
    void nullFieldsStayNull() throws Exception {
        when(bulkManagementService.applyToAllEnvironments(any(), any(), any(), any()))
                .thenReturn(List.of(log("local", BulkOperationStatus.SKIPPED, null)));

        runner.runSlugInstall(21L, 3L, BulkOperationType.PLUGIN_INSTALL, "akismet", 5L);

        JsonNode entry = terminalPayload("done").get("logs").get(0);
        assertEquals("SKIPPED", entry.get("status").asText());
        assertTrue(entry.get("errorMessage").isNull());
        assertTrue(entry.get("stackTrace").isNull());
    }

    @Test
    @DisplayName("createdAt が無いログも失敗せず null で載る")
    void nullCreatedAt() throws Exception {
        BulkOperationLog noTime = log("local", BulkOperationStatus.SUCCESS, null);
        noTime.setCreatedAt(null);
        when(bulkManagementService.applyToAllEnvironments(any(), any(), any(), any())).thenReturn(List.of(noTime));

        runner.runSlugInstall(21L, 3L, BulkOperationType.PLUGIN_INSTALL, "akismet", 5L);

        assertTrue(terminalPayload("done").get("logs").get(0).get("createdAt").isNull());
    }
}
