package com.letsblog.media.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.media.ai.ComfyUiCheckpointStorageService;
import com.letsblog.media.ai.DownloadProgress;
import com.letsblog.media.client.GenerationJobClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * ModelInstallJobRunnerの回帰テスト(#573 stage2でlegacy-apiから移設)。GenerationJob自体は
 * legacy-apiが所有するため、進捗・完了・失敗の反映がGenerationJobClient経由のHTTP呼び出しに
 * 置き換わったことを検証する(元はGenerationJobRepositoryへの直接JPA書き込みだった)。
 * {@code @Async}は素のオブジェクトへの直接呼び出しでは効かない(Springプロキシを経由しないため)
 * ため、このテストではメソッドを同期的に呼び出して検証する。
 */
@ExtendWith(MockitoExtension.class)
class ModelInstallJobRunnerTest {

    @Mock
    private ComfyUiCheckpointStorageService comfyUiCheckpointStorageService;
    @Mock
    private GenerationJobClient generationJobClient;

    private ModelInstallJobRunner runner;

    @BeforeEach
    void setUp() {
        runner = new ModelInstallJobRunner(comfyUiCheckpointStorageService, generationJobClient, new ObjectMapper());
    }

    @SuppressWarnings("unchecked")
    @Test
    void runComfyUiDownload_成功時はdoneとしてBearerトークンを転送して報告する() {
        doAnswer(invocation -> {
            Consumer<DownloadProgress> onProgress = invocation.getArgument(2);
            onProgress.accept(new DownloadProgress(100, 100));
            return null;
        }).when(comfyUiCheckpointStorageService).downloadCheckpoint(anyString(), anyString(), any(Consumer.class));

        runner.runComfyUiDownload(1L, "https://example.com/model.safetensors", "model.safetensors", "Bearer token-123");

        // 進捗報告("running")と完了報告("done")の両方が、同じjobId・Bearerトークンで呼ばれる。
        verify(generationJobClient).updateStatus(eq(1L), eq("done"), anyString(), eq("Bearer token-123"));
    }

    @Test
    void runComfyUiDownload_失敗時はfailedとして報告する() {
        doThrow(new RuntimeException("接続できません"))
                .when(comfyUiCheckpointStorageService).downloadCheckpoint(anyString(), anyString(), any());

        runner.runComfyUiDownload(2L, "https://example.com/model.safetensors", "model.safetensors", "Bearer token-456");

        verify(generationJobClient).updateStatus(eq(2L), eq("failed"), anyString(), eq("Bearer token-456"));
    }

    @Test
    void runComfyUiDelete_成功時はdoneとして報告する() {
        runner.runComfyUiDelete(3L, "model.safetensors", "Bearer token-789");

        verify(comfyUiCheckpointStorageService).deleteCheckpoint("model.safetensors");
        verify(generationJobClient).updateStatus(eq(3L), eq("done"), anyString(), eq("Bearer token-789"));
    }

    @Test
    void runComfyUiDelete_失敗時はfailedとして報告する() {
        doThrow(new RuntimeException("ファイルが見つかりません")).when(comfyUiCheckpointStorageService).deleteCheckpoint("missing.safetensors");

        runner.runComfyUiDelete(4L, "missing.safetensors", "Bearer token-000");

        verify(generationJobClient).updateStatus(eq(4L), eq("failed"), anyString(), eq("Bearer token-000"));
    }
}
