package com.letsblog.media.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.media.ai.AiServiceException;
import com.letsblog.media.ai.ComfyUiUnreachableException;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.media.dto.AiImageRequest;
import com.letsblog.media.dto.AiImageResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;

/**
 * 画像生成ジョブ(issue #1405)の実行本体。{@link ModelInstallJobRunner}と同型で、
 * {@code @Async}が自己呼び出しに効かないため受理側の{@link ImageGenerationJobStarter}とは
 * 別クラスにしている。進捗・完了・失敗は{@link GenerationJobClient#updateStatus}(サービス自身の
 * Client Credentialsで認証。#1083)でジョブへ反映するので、呼び出し元ユーザーの
 * Bearerトークンは持たない。
 *
 * <p>結果には生成画像のIDだけを残し、Base64は載せない(#1112のオンヒープ保持を非同期経路で
 * 再現しないため。生成画像自体は{@code generated_images}に永続化済み)。失敗には
 * 利用者が読める{@code error}に加え、原因を区別できる{@code errorType}を残す:
 * {@value #ERROR_PROHIBITED_CONTENT} / {@value #ERROR_PROVIDER_UNREACHABLE} /
 * {@value #ERROR_RATE_LIMITED} / {@value #ERROR_GENERATION_FAILED}。
 */
@Service
public class ImageGenerationJobRunner {

    static final String ERROR_PROHIBITED_CONTENT = "prohibited_content";
    static final String ERROR_PROVIDER_UNREACHABLE = "provider_unreachable";
    static final String ERROR_RATE_LIMITED = "rate_limited";
    static final String ERROR_GENERATION_FAILED = "generation_failed";

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationJobRunner.class);

    private final ImageGenerationService imageGenerationService;
    private final GenerationJobClient generationJobClient;
    private final ObjectMapper objectMapper;
    private final ImageGenerationJobTracker tracker;

    public ImageGenerationJobRunner(
            ImageGenerationService imageGenerationService,
            GenerationJobClient generationJobClient,
            ObjectMapper objectMapper,
            ImageGenerationJobTracker tracker) {
        this.tracker = tracker;
        this.imageGenerationService = imageGenerationService;
        this.generationJobClient = generationJobClient;
        this.objectMapper = objectMapper;
    }

    @Async("imageGenerationExecutor")
    public void run(Long jobId, AiImageRequest request) {
        try {
            reportProgress(jobId, 0);
            ImageGenerationService.BatchOutcome outcome = imageGenerationService.generateBatch(
                    request, false, (done, total) -> reportProgress(jobId, percent(done, total)));
            // 終端通知はトラッカーの排他の中で書く。ハートビートが後からrunningで上書きしないため。
            tracker.complete(jobId, () ->
                    generationJobClient.updateStatus(jobId, "done", toJson(donePayload(outcome))));
        } catch (RuntimeException e) {
            log.warn("Image generation job {} failed", jobId, e);
            tracker.complete(jobId, () ->
                    generationJobClient.updateStatus(jobId, "failed", toJson(failurePayload(e))));
        }
    }

    private void reportProgress(Long jobId, int percent) {
        tracker.update(jobId, "generating", percent);
        generationJobClient.updateStatus(
                jobId, "running", toJson(new JobProgressPayload("generating", percent, null, null)));
    }

    private static int percent(int done, int total) {
        return total > 0 ? (int) Math.round(done * 100.0 / total) : 0;
    }

    private static Map<String, Object> donePayload(ImageGenerationService.BatchOutcome outcome) {
        List<Long> imageIds = outcome.images().stream().map(AiImageResponse::id).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imageIds", imageIds);
        result.put("count", imageIds.size());
        result.put("succeededRepeats", outcome.succeededRepeats());
        result.put("failedRepeats", outcome.failedRepeats());
        result.put("attemptedRepeats", outcome.attemptedRepeats());
        result.put("aborted", outcome.aborted());
        return result;
    }

    static Map<String, Object> failurePayload(RuntimeException e) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", String.valueOf(e.getMessage()));
        result.put("errorType", classify(e));
        return result;
    }

    private static String classify(RuntimeException e) {
        if (e instanceof ProhibitedContentException) {
            return ERROR_PROHIBITED_CONTENT;
        }
        if (e instanceof ComfyUiUnreachableException) {
            return ERROR_PROVIDER_UNREACHABLE;
        }
        if (e instanceof AiServiceException
                && e.getCause() instanceof HttpStatusCodeException http
                && http.getStatusCode().value() == 429) {
            return ERROR_RATE_LIMITED;
        }
        return ERROR_GENERATION_FAILED;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
