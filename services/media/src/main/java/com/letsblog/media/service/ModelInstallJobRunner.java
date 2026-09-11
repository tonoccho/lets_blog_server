package com.letsblog.media.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.media.ai.ComfyUiCheckpointStorageService;
import com.letsblog.media.client.GenerationJobClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * ComfyUIチェックポイントダウンロードなど、数分かかりうる処理をバックグラウンドスレッドで実行する。
 * Spring の {@code @Async} は同一クラス内の自己呼び出しには効かない(プロキシを経由しないため)ため、
 * ジョブを起動するコントローラ({@link com.letsblog.media.controller.ComfyUiCheckpointController})
 * とは別クラスに分離している。legacy-apiから移設(issue #573 stage2)。ジョブ自体(GenerationJob)は
 * 引き続きlegacy-apiが所有するため、進捗・完了・失敗の反映は{@link GenerationJobClient}経由の
 * HTTP呼び出しで行う(元々はGenerationJobRepositoryへの直接JPA書き込みだった)。
 *
 * <p>以前は、ジョブを起動した同期リクエスト(ComfyUiCheckpointControllerがlegacy-apiから
 * 転送を受けた時点)のBearerトークンを、このバックグラウンドスレッドの生存期間全体で使い回して
 * いた。しかしKeycloakの{@code accessTokenLifespan}(既定300秒)より長くかかる大きな
 * チェックポイントのダウンロード中にトークンが失効し、完了/失敗通知が握りつぶされて
 * ジョブがDB上{@code running}のまま残る不具合(issue #1083)の原因になっていた。
 * {@link GenerationJobClient#updateStatus}が自身のClient Credentialsトークンで認証する
 * ように変更(#1083)されたため、このクラスはもはやBearerトークンを保持・転送しない。
 */
@Service
public class ModelInstallJobRunner {

    private static final Logger log = LoggerFactory.getLogger(ModelInstallJobRunner.class);
    private static final long PROGRESS_UPDATE_INTERVAL_MS = 500;

    private final ComfyUiCheckpointStorageService comfyUiCheckpointStorageService;
    private final GenerationJobClient generationJobClient;
    private final ObjectMapper objectMapper;

    public ModelInstallJobRunner(
            ComfyUiCheckpointStorageService comfyUiCheckpointStorageService,
            GenerationJobClient generationJobClient,
            ObjectMapper objectMapper) {
        this.comfyUiCheckpointStorageService = comfyUiCheckpointStorageService;
        this.generationJobClient = generationJobClient;
        this.objectMapper = objectMapper;
    }

    @Async("modelInstallExecutor")
    public void runComfyUiDownload(Long jobId, String url, String fileName) {
        runJob(jobId, reporter -> comfyUiCheckpointStorageService.downloadCheckpoint(url, fileName, progress -> {
            Integer percent = progress.totalBytes() > 0
                    ? (int) Math.round(progress.bytesDownloaded() * 100.0 / progress.totalBytes())
                    : null;
            reporter.report(
                    "downloading",
                    percent,
                    progress.bytesDownloaded(),
                    progress.totalBytes() > 0 ? progress.totalBytes() : null);
        }));
    }

    @Async("modelInstallExecutor")
    public void runComfyUiDelete(Long jobId, String fileName) {
        runJob(jobId, reporter -> comfyUiCheckpointStorageService.deleteCheckpoint(fileName));
    }

    @FunctionalInterface
    private interface JobAction {
        void run(ProgressReporter reporter);
    }

    @FunctionalInterface
    private interface ProgressReporter {
        void report(String phase, Integer percent, Long bytesDone, Long bytesTotal);
    }

    private void runJob(Long jobId, JobAction action) {
        long[] lastReportedAt = {0L};
        ProgressReporter reporter = (phase, percent, bytesDone, bytesTotal) -> {
            long now = System.currentTimeMillis();
            if (now - lastReportedAt[0] < PROGRESS_UPDATE_INTERVAL_MS) {
                return;
            }
            lastReportedAt[0] = now;
            generationJobClient.updateStatus(
                    jobId, "running", toJson(new JobProgressPayload(phase, percent, bytesDone, bytesTotal)));
        };

        try {
            action.run(reporter);
            generationJobClient.updateStatus(jobId, "done", toJson(Map.of("success", "true")));
        } catch (RuntimeException e) {
            log.warn("Model install job {} failed", jobId, e);
            generationJobClient.updateStatus(
                    jobId, "failed", toJson(Map.of("error", String.valueOf(e.getMessage()))));
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
