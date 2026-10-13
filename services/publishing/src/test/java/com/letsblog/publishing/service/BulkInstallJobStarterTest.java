package com.letsblog.publishing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.client.GenerationJobBridgeException;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.common.client.GenerationJobSummary;
import com.letsblog.publishing.domain.BulkOperationType;
import com.letsblog.publishing.dto.ApplyToAllEnvironmentsRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;

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

/** 一括インストールジョブの受理側(issue #1702)。project-serviceの ProjectEnvironmentSyncJobStarterTest と同型。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("publishing-service: 全環境インストールジョブの受理(issue #1702)")
class BulkInstallJobStarterTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 1, 2, 3);
    private static final BulkUploadStorageService.StoredZip STORED =
            new BulkUploadStorageService.StoredZip("3/abc.zip", "abc", "my-plugin.zip");

    @Mock
    private GenerationJobClient generationJobClient;
    @Mock
    private BulkInstallJobRunner runner;
    @Mock
    private BulkManagementService bulkManagementService;
    @Mock
    private BulkUploadStorageService storageService;
    @Mock
    private CurrentActorService currentActorService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private BulkInstallJobStarter starter;

    @BeforeEach
    void setUp() {
        starter = new BulkInstallJobStarter(
                generationJobClient, runner, bulkManagementService, storageService, currentActorService, objectMapper);
    }

    private static MockMultipartFile zip() {
        return new MockMultipartFile("file", "my-plugin.zip", "application/zip", new byte[] {1, 2, 3});
    }

    @Test
    @DisplayName("ジョブ種別は安定した非AIの名前")
    void jobTypesAreStable() {
        assertEquals("bulk_slug_install", BulkInstallJobStarter.SLUG_JOB_TYPE);
        assertEquals("bulk_zip_install", BulkInstallJobStarter.ZIP_JOB_TYPE);
    }

    @Test
    @DisplayName("slug: リクエストスレッドで操作者を解決し、利用者のBearerでジョブを作り、同じ操作者IDをランナーへ渡す")
    void startsSlugJob() throws Exception {
        when(currentActorService.getCurrentActorId()).thenReturn(5L);
        when(currentActorService.getAuthorizationHeader()).thenReturn("Bearer abc");
        GenerationJobSummary job = new GenerationJobSummary(21L, "bulk_slug_install", "running", NOW, NOW);
        when(generationJobClient.create(eq("bulk_slug_install"), any(), eq("Bearer abc"))).thenReturn(job);

        GenerationJobSummary result =
                starter.startSlugInstall(3L, new ApplyToAllEnvironmentsRequest(BulkOperationType.PLUGIN_INSTALL, "akismet"));

        assertSame(job, result);
        verify(bulkManagementService).validateSlugInstall(BulkOperationType.PLUGIN_INSTALL, "akismet");
        verify(runner).runSlugInstall(21L, 3L, BulkOperationType.PLUGIN_INSTALL, "akismet", 5L);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).create(eq("bulk_slug_install"), payload.capture(), eq("Bearer abc"));
        JsonNode json = objectMapper.readTree(payload.getValue());
        // キューUIが結果の遷移先を決めるのに projectId を読む。
        assertEquals(3L, json.get("projectId").asLong());
        assertEquals("PLUGIN_INSTALL", json.get("operationType").asText());
        assertEquals("akismet", json.get("value").asText());
    }

    @Test
    @DisplayName("slug: 入力が誤っていれば、ジョブを作らず検証の例外をそのまま伝える")
    void slugValidationFailureCreatesNoJob() {
        doThrow(new IllegalArgumentException("slugを入力してください")).when(bulkManagementService)
                .validateSlugInstall(any(), any());

        assertThrows(IllegalArgumentException.class, () -> starter.startSlugInstall(
                3L, new ApplyToAllEnvironmentsRequest(BulkOperationType.PLUGIN_INSTALL, " ")));

        verify(generationJobClient, never()).create(any(), any(), any());
        verify(runner, never()).runSlugInstall(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("zip: 受理スレッドで保存し、保存先・sha256・元のファイル名をジョブの要求内容に載せ、ランナーへ渡す")
    void startsZipJob() throws Exception {
        when(currentActorService.getCurrentActorId()).thenReturn(5L);
        when(currentActorService.getAuthorizationHeader()).thenReturn("Bearer abc");
        when(storageService.store(eq(3L), any(), eq("my-plugin.zip"))).thenReturn(STORED);
        GenerationJobSummary job = new GenerationJobSummary(22L, "bulk_zip_install", "running", NOW, NOW);
        when(generationJobClient.create(eq("bulk_zip_install"), any(), eq("Bearer abc"))).thenReturn(job);

        GenerationJobSummary result = starter.startZipInstall(3L, BulkOperationType.THEME_INSTALL, zip());

        assertSame(job, result);
        verify(bulkManagementService).validateZipInstall(eq(BulkOperationType.THEME_INSTALL), any());
        verify(runner).runZipInstall(22L, 3L, BulkOperationType.THEME_INSTALL, STORED, 5L);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).create(eq("bulk_zip_install"), payload.capture(), eq("Bearer abc"));
        JsonNode json = objectMapper.readTree(payload.getValue());
        assertEquals(3L, json.get("projectId").asLong());
        assertEquals("THEME_INSTALL", json.get("operationType").asText());
        assertEquals("3/abc.zip", json.get("storagePath").asText());
        assertEquals("abc", json.get("sha256").asText());
        assertEquals("my-plugin.zip", json.get("originalFilename").asText());
    }

    @Test
    @DisplayName("zip: 入力が誤っていれば、保存もジョブ作成もしない")
    void zipValidationFailureStoresNothing() throws Exception {
        doThrow(new IllegalArgumentException("zipファイル(.zip)を指定してください")).when(bulkManagementService)
                .validateZipInstall(any(), any());

        assertThrows(IllegalArgumentException.class,
                () -> starter.startZipInstall(3L, BulkOperationType.PLUGIN_INSTALL, zip()));

        verify(storageService, never()).store(any(), any(), any());
        verify(generationJobClient, never()).create(any(), any(), any());
    }

    @Test
    @DisplayName("zip: 保存に失敗すればジョブを作らず IOException を伝える")
    void zipStoreFailurePropagates() throws Exception {
        when(storageService.store(any(), any(), any())).thenThrow(new IOException("disk full"));

        assertThrows(IOException.class, () -> starter.startZipInstall(3L, BulkOperationType.PLUGIN_INSTALL, zip()));

        verify(generationJobClient, never()).create(any(), any(), any());
    }

    @Test
    @DisplayName("実行枠と待ち行列が満杯なら、ジョブを queue_full の failed にして返す(slug)")
    void queueFullForSlug() throws Exception {
        when(generationJobClient.create(any(), any(), any()))
                .thenReturn(new GenerationJobSummary(23L, "bulk_slug_install", "running", NOW, NOW));
        doThrow(new TaskRejectedException("full")).when(runner).runSlugInstall(any(), any(), any(), any(), any());

        GenerationJobSummary result =
                starter.startSlugInstall(3L, new ApplyToAllEnvironmentsRequest(BulkOperationType.PLUGIN_INSTALL, "akismet"));

        assertEquals("failed", result.status());
        assertEquals(23L, result.id());
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).updateStatus(eq(23L), eq("failed"), payload.capture());
        JsonNode json = objectMapper.readTree(payload.getValue());
        assertEquals("queue_full", json.get("errorType").asText());
        assertFalse(json.get("error").asText().isBlank());
    }

    @Test
    @DisplayName("実行枠と待ち行列が満杯なら、ジョブを queue_full の failed にして返す(zip)")
    void queueFullForZip() throws Exception {
        when(storageService.store(any(), any(), any())).thenReturn(STORED);
        when(generationJobClient.create(any(), any(), any()))
                .thenReturn(new GenerationJobSummary(24L, "bulk_zip_install", "running", NOW, NOW));
        doThrow(new TaskRejectedException("full")).when(runner).runZipInstall(any(), any(), any(), any(), any());

        GenerationJobSummary result = starter.startZipInstall(3L, BulkOperationType.PLUGIN_INSTALL, zip());

        assertEquals("failed", result.status());
        verify(generationJobClient).updateStatus(eq(24L), eq("failed"), any());
    }

    @Test
    @DisplayName("ジョブを作れなければ(ai-service 不達)ランナーを起動せず例外を伝える")
    void createFailurePropagates() {
        when(generationJobClient.create(any(), any(), any())).thenThrow(new GenerationJobBridgeException("down", null));

        assertThrows(GenerationJobBridgeException.class, () -> starter.startSlugInstall(
                3L, new ApplyToAllEnvironmentsRequest(BulkOperationType.PLUGIN_INSTALL, "akismet")));

        verify(runner, never()).runSlugInstall(any(), any(), any(), any(), any());
    }
}
