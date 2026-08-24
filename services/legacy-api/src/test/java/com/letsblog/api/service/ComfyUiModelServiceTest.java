package com.letsblog.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.ai.ComfyUiClient;
import com.letsblog.api.ai.MediaComfyUiClient;
import com.letsblog.api.domain.GenerationJob;
import com.letsblog.api.dto.GenerationJobResponse;
import com.letsblog.api.repository.GenerationJobRepository;
import com.letsblog.api.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ComfyUiModelServiceの回帰テスト。#573 stage2で、実際のチェックポイントダウンロード/削除の実行
 * (旧ModelInstallJobRunner)がmedia-serviceへ移設されたのに伴い、startInstall/startDeleteが
 * legacy-api側でGenerationJobを作成した上でMediaComfyUiClient経由で起動をトリガーするだけに
 * なったことを検証する(ジョブ自体の作成・所有はlegacy-apiに残る)。
 */
@ExtendWith(MockitoExtension.class)
class ComfyUiModelServiceTest {

    @Mock
    private ComfyUiClient comfyUiClient;
    @Mock
    private MediaComfyUiClient mediaComfyUiClient;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ProjectImageSettingsService projectImageSettingsService;
    @Mock
    private GenerationJobRepository generationJobRepository;

    private ComfyUiModelService service;

    @BeforeEach
    void setUp() {
        service = new ComfyUiModelService(
                comfyUiClient, mediaComfyUiClient, projectRepository, projectImageSettingsService,
                generationJobRepository, new ObjectMapper(), "v1-5-pruned-emaonly.safetensors");
    }

    @Test
    void startInstall_ジョブをlegacyApi側で作成しmediaComfyUiClient経由で起動する() {
        when(generationJobRepository.save(any(GenerationJob.class))).thenAnswer(invocation -> {
            GenerationJob job = invocation.getArgument(0);
            job.setId(42L);
            return job;
        });

        GenerationJobResponse response = service.startInstall("https://example.com/model.safetensors", "model.safetensors");

        assertEquals(42L, response.id());
        assertEquals("comfyui_checkpoint_download", response.type());
        assertEquals("running", response.status());

        ArgumentCaptor<GenerationJob> savedJob = ArgumentCaptor.forClass(GenerationJob.class);
        verify(generationJobRepository).save(savedJob.capture());
        assertEquals("comfyui_checkpoint_download", savedJob.getValue().getType());

        verify(mediaComfyUiClient).startInstall(42L, "https://example.com/model.safetensors", "model.safetensors");
    }

    @Test
    void startInstall_httpsでないURLはIllegalArgumentException_mediaComfyUiClientは呼ばれない() {
        assertThrows(IllegalArgumentException.class, () -> service.startInstall("ftp://example.com/model.safetensors", "model.safetensors"));
        verify(generationJobRepository, never()).save(any());
        verify(mediaComfyUiClient, never()).startInstall(any(), any(), any());
    }

    @Test
    void startDelete_ジョブをlegacyApi側で作成しmediaComfyUiClient経由で起動する() {
        when(generationJobRepository.save(any(GenerationJob.class))).thenAnswer(invocation -> {
            GenerationJob job = invocation.getArgument(0);
            job.setId(7L);
            return job;
        });

        GenerationJobResponse response = service.startDelete("model.safetensors");

        assertEquals(7L, response.id());
        assertEquals("comfyui_checkpoint_delete", response.type());
        verify(mediaComfyUiClient).startDelete(7L, "model.safetensors");
    }

    @Test
    void getSelectedCheckpoint_未選択ならグローバルデフォルトにフォールバックする() {
        when(projectRepository.existsById(1L)).thenReturn(true);
        when(projectImageSettingsService.getComfyuiCheckpoint(1L)).thenReturn(null);

        assertEquals("v1-5-pruned-emaonly.safetensors", service.getSelectedCheckpoint(1L));
    }

    @Test
    void getSelectedCheckpointOrGlobalDefault_projectId未指定ならプロジェクト存在確認せずグローバルデフォルトを返す() {
        assertEquals("v1-5-pruned-emaonly.safetensors", service.getSelectedCheckpointOrGlobalDefault(null));
        verify(projectRepository, never()).existsById(any());
    }
}
