package com.letsblog.media.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.media.ai.ImageProvider;
import com.letsblog.media.client.GenerationJobClient;
import com.letsblog.media.client.GenerationJobSummary;
import com.letsblog.media.dto.AiImageRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

/**
 * 画像生成を非同期ジョブとして受理する(issue #1405)。要求を検証して{@code generation_jobs}に
 * ジョブを作り、ジョブIDを返して即座に戻る。生成本体は別クラスの{@link ImageGenerationJobRunner}
 * ({@code @Async})が担う。{@code ComfyUiModelService}が{@link ModelInstallJobRunner}を起動するのと同型。
 *
 * <p>ジョブの{@code type}は{@link #JOB_TYPE}。プロバイダ(ComfyUI/ChatGPT)ごとに分かれる同期経路の
 * {@code comfyui_image}/{@code chatgpt_image}とは別で、プロバイダに依らず1種類にする
 * (結果画面への遷移先をジョブ種別で決める側が、プロバイダを意識しなくて済むように)。
 * 命名は既存の{@code <対象>_<操作>}(例: {@code comfyui_checkpoint_download})に倣う。
 */
@Service
public class ImageGenerationJobStarter {

    /** 非同期の画像生成ジョブの種別。結果画面への遷移先の判定に使われるので変えないこと。 */
    public static final String JOB_TYPE = "image_generation";

    private final ImageGenerationService imageGenerationService;
    private final ImageGenerationJobRunner runner;
    private final GenerationJobClient generationJobClient;
    private final ObjectMapper objectMapper;
    private final ImageGenerationJobTracker tracker;

    public ImageGenerationJobStarter(
            ImageGenerationService imageGenerationService,
            ImageGenerationJobRunner runner,
            GenerationJobClient generationJobClient,
            ObjectMapper objectMapper,
            ImageGenerationJobTracker tracker) {
        this.tracker = tracker;
        this.imageGenerationService = imageGenerationService;
        this.runner = runner;
        this.generationJobClient = generationJobClient;
        this.objectMapper = objectMapper;
    }

    /**
     * @param bearerToken 呼び出し元の{@code Authorization}ヘッダー値。ジョブ作成にだけ使う
     *                    (以降の更新はランナー側がサービス自身のトークンで行う。#1083)。
     */
    public GenerationJobSummary start(AiImageRequest request, String bearerToken) {
        ImageProvider provider = imageGenerationService.requireAcceptable(request);
        GenerationJobSummary job = generationJobClient.create(JOB_TYPE, requestPayload(request, provider), bearerToken);
        // 待ち行列で待つ間もstale回収されないよう、ランナーへ渡す前に追跡へ入れる。
        tracker.track(job.id());
        try {
            runner.run(job.id(), request);
        } catch (TaskRejectedException e) {
            // 実行枠と待ち行列が満杯。ジョブを作ってしまっているので、running のまま
            // 取り残さず、理由が読める failed にする。応答も実際の状態(failed)を返す。
            tracker.complete(job.id(), () -> generationJobClient.updateStatus(job.id(), "failed", toJson(Map.of(
                    "error", "画像生成の待ち行列が満杯です。しばらくしてからもう一度要求してください",
                    "errorType", "queue_full"))));
            return new GenerationJobSummary(job.id(), job.type(), "failed", job.createdAt(), job.updatedAt());
        }
        return job;
    }

    private String requestPayload(AiImageRequest request, ImageProvider provider) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("prompt", request.prompt());
        payload.put("provider", provider.name());
        payload.put("batchSize", request.batchSize() != null ? request.batchSize() : 1);
        payload.put("batchCount", request.batchCount() != null ? request.batchCount() : 1);
        return toJson(payload);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
