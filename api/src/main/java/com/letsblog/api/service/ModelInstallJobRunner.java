package com.letsblog.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.ai.ComfyUiCheckpointStorageService;
import com.letsblog.api.domain.GenerationJob;
import com.letsblog.api.repository.GenerationJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * ComfyUIチェックポイントダウンロードなど、数分かかりうる処理をバックグラウンドスレッドで実行する。
 * Spring の {@code @Async} は同一クラス内の自己呼び出しには効かない(プロキシを経由しないため)ため、
 * ジョブを起動する {@link ComfyUiModelService} とは別クラスに分離している。
 */
@Service
public class ModelInstallJobRunner {

    private static final Logger log = LoggerFactory.getLogger(ModelInstallJobRunner.class);
    private static final long PROGRESS_UPDATE_INTERVAL_MS = 500;

    private final ComfyUiCheckpointStorageService comfyUiCheckpointStorageService;
    private final GenerationJobRepository generationJobRepository;
    private final ObjectMapper objectMapper;

    public ModelInstallJobRunner(
            ComfyUiCheckpointStorageService comfyUiCheckpointStorageService,
            GenerationJobRepository generationJobRepository,
            ObjectMapper objectMapper) {
        this.comfyUiCheckpointStorageService = comfyUiCheckpointStorageService;
        this.generationJobRepository = generationJobRepository;
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
            generationJobRepository.findById(jobId).ifPresent(job -> {
                job.setResultPayload(toJson(new JobProgressPayload(phase, percent, bytesDone, bytesTotal)));
                generationJobRepository.save(job);
            });
        };

        GenerationJob job = generationJobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        try {
            action.run(reporter);
            job = generationJobRepository.findById(jobId).orElse(job);
            job.setStatus("done");
            job.setResultPayload(toJson(Map.of("success", "true")));
        } catch (RuntimeException e) {
            log.warn("Model install job {} failed", jobId, e);
            job = generationJobRepository.findById(jobId).orElse(job);
            job.setStatus("failed");
            job.setResultPayload(toJson(Map.of("error", String.valueOf(e.getMessage()))));
        }
        generationJobRepository.save(job);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
