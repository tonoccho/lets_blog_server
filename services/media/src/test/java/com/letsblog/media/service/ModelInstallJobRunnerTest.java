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
 *
 * <p>issue #1083: 起動時の呼び出し元Bearerトークンを非同期ジョブの生存期間全体で引き回すのが
 * 根本原因(#1083)だったため、bearerToken引数自体を廃止した。GenerationJobClient側が
 * サービス自身のトークンで認証するため、このクラスはもはやBearerトークンを保持しない。
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
    void runComfyUiDownload_成功時はdoneとして報告する() {
        doAnswer(invocation -> {
            Consumer<DownloadProgress> onProgress = invocation.getArgument(2);
            onProgress.accept(new DownloadProgress(100, 100));
            return null;
        }).when(comfyUiCheckpointStorageService).downloadCheckpoint(anyString(), anyString(), any(Consumer.class));

        runner.runComfyUiDownload(1L, "https://example.com/model.safetensors", "model.safetensors");

        // 進捗報告("running")と完了報告("done")の両方が、同じjobIdで呼ばれる(bearerTokenは
        // もはや引き回さない。#1083)。
        verify(generationJobClient).updateStatus(eq(1L), eq("done"), anyString());
    }

    @SuppressWarnings("unchecked")
    @Test
    void runComfyUiDownload_totalBytes不明でも進捗を報告できパーセントはnull() {
        doAnswer(invocation -> {
            Consumer<DownloadProgress> onProgress = invocation.getArgument(2);
            // totalBytes<=0(不明)の場合、percentはnullになる分岐。連続で呼んで
            // 直近報告からの間隔が短い(スロットリングでスキップされる)分岐も併せて通す。
            onProgress.accept(new DownloadProgress(10, 0));
            onProgress.accept(new DownloadProgress(20, 0));
            return null;
        }).when(comfyUiCheckpointStorageService).downloadCheckpoint(anyString(), anyString(), any(Consumer.class));

        runner.runComfyUiDownload(5L, "https://example.com/model.safetensors", "model.safetensors");

        verify(generationJobClient).updateStatus(eq(5L), eq("done"), anyString());
    }

    @Test
    void runComfyUiDownload_失敗時はfailedとして報告する() {
        doThrow(new RuntimeException("接続できません"))
                .when(comfyUiCheckpointStorageService).downloadCheckpoint(anyString(), anyString(), any());

        runner.runComfyUiDownload(2L, "https://example.com/model.safetensors", "model.safetensors");

        verify(generationJobClient).updateStatus(eq(2L), eq("failed"), anyString());
    }

    @Test
    void runComfyUiDelete_成功時はdoneとして報告する() {
        runner.runComfyUiDelete(3L, "model.safetensors");

        verify(comfyUiCheckpointStorageService).deleteCheckpoint("model.safetensors");
        verify(generationJobClient).updateStatus(eq(3L), eq("done"), anyString());
    }

    @Test
    void runComfyUiDelete_失敗時はfailedとして報告する() {
        doThrow(new RuntimeException("ファイルが見つかりません")).when(comfyUiCheckpointStorageService).deleteCheckpoint("missing.safetensors");

        runner.runComfyUiDelete(4L, "missing.safetensors");

        verify(generationJobClient).updateStatus(eq(4L), eq("failed"), anyString());
    }
}
