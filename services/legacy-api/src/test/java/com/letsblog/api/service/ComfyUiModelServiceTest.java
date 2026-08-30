package com.letsblog.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.ai.ComfyUiClient;
import com.letsblog.api.ai.MediaComfyUiClient;
import com.letsblog.api.client.GenerationJobClient;
import com.letsblog.api.client.GenerationJobSummary;
import com.letsblog.api.client.ProjectServiceClient;
import com.letsblog.api.dto.GenerationJobResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ComfyUiModelServiceの回帰テスト。issue #574でgeneration_jobsテーブルの所有権がai-serviceへ
 * 移管されたことに伴い、startInstall/startDeleteがGenerationJobClient経由でai-serviceへ
 * ジョブ作成を委譲したうえでMediaComfyUiClient経由で起動をトリガーすることを検証する
 * (以前はGenerationJobRepositoryへ直接保存していた)。
 */
@ExtendWith(MockitoExtension.class)
class ComfyUiModelServiceTest {

    @Mock
    private ComfyUiClient comfyUiClient;
    @Mock
    private MediaComfyUiClient mediaComfyUiClient;
    @Mock
    private ProjectServiceClient projectServiceClient;
    @Mock
    private ProjectImageSettingsService projectImageSettingsService;
    @Mock
    private GenerationJobClient generationJobClient;

    private ComfyUiModelService service;

    @BeforeEach
    void setUp() {
        service = new ComfyUiModelService(
                comfyUiClient, mediaComfyUiClient, projectServiceClient, projectImageSettingsService,
                generationJobClient, new ObjectMapper(), "v1-5-pruned-emaonly.safetensors");
    }

    private ProjectServiceClient.ProjectBridge existingProject(Long id) {
        LocalDateTime now = LocalDateTime.now();
        return new ProjectServiceClient.ProjectBridge(id, "テストプロジェクト", "test", "test", null, null, null, null, now, now);
    }

    @Test
    void startInstall_ジョブをai_service経由で作成しmediaComfyUiClient経由で起動する() {
        when(generationJobClient.create(anyString(), anyString())).thenReturn(
                new GenerationJobSummary(42L, "comfyui_checkpoint_download", "running",
                        LocalDateTime.now(), LocalDateTime.now()));

        GenerationJobResponse response = service.startInstall("https://example.com/model.safetensors", "model.safetensors");

        assertEquals(42L, response.id());
        assertEquals("comfyui_checkpoint_download", response.type());
        assertEquals("running", response.status());

        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).create(typeCaptor.capture(), anyString());
        assertEquals("comfyui_checkpoint_download", typeCaptor.getValue());

        verify(mediaComfyUiClient).startInstall(42L, "https://example.com/model.safetensors", "model.safetensors");
    }

    @Test
    void startInstall_httpsでないURLはIllegalArgumentException_mediaComfyUiClientは呼ばれない() {
        assertThrows(IllegalArgumentException.class, () -> service.startInstall("ftp://example.com/model.safetensors", "model.safetensors"));
        verify(generationJobClient, never()).create(any(), any());
        verify(mediaComfyUiClient, never()).startInstall(any(), any(), any());
    }

    @Test
    void startDelete_ジョブをai_service経由で作成しmediaComfyUiClient経由で起動する() {
        when(generationJobClient.create(anyString(), anyString())).thenReturn(
                new GenerationJobSummary(7L, "comfyui_checkpoint_delete", "running",
                        LocalDateTime.now(), LocalDateTime.now()));

        GenerationJobResponse response = service.startDelete("model.safetensors");

        assertEquals(7L, response.id());
        assertEquals("comfyui_checkpoint_delete", response.type());
        verify(mediaComfyUiClient).startDelete(7L, "model.safetensors");
    }

    @Test
    void getSelectedCheckpoint_未選択ならグローバルデフォルトにフォールバックする() {
        when(projectServiceClient.getProject(1L)).thenReturn(existingProject(1L));
        when(projectImageSettingsService.getComfyuiCheckpoint(1L)).thenReturn(null);

        assertEquals("v1-5-pruned-emaonly.safetensors", service.getSelectedCheckpoint(1L));
    }

    @Test
    void getSelectedCheckpointOrGlobalDefault_projectId未指定ならプロジェクト存在確認せずグローバルデフォルトを返す() {
        assertEquals("v1-5-pruned-emaonly.safetensors", service.getSelectedCheckpointOrGlobalDefault(null));
        verify(projectServiceClient, never()).getProject(any());
    }
}
