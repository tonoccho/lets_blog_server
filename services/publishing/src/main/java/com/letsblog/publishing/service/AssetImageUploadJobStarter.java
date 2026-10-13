package com.letsblog.publishing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.common.client.GenerationJobSummary;
import com.letsblog.publishing.client.MediaGeneratedImageClient;
import com.letsblog.publishing.client.MediaSettingsBridgeClient;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

/**
 * アセット画像の全環境アップロードを非同期ジョブとして受理する(issue #1764)。{@link BulkInstallJobStarter}と同型。
 * 利用者のBearerを転送する{@link MediaGeneratedImageClient}と{@link MediaSettingsBridgeClient}は、
 * リクエストの無いランナーでは使えないので、画像・長辺px・操作者はここ(リクエストスレッド)で解決し、
 * ランナーへメモリで渡す。publishing側に画像の複製は保存しない。
 */
@Service
public class AssetImageUploadJobStarter {

    /** ジョブ種別。キューUIの遷移先の判定と統合操作ログの分類に使われるので変えないこと。 */
    public static final String JOB_TYPE = "asset_image_upload";

    private final GenerationJobClient generationJobClient;
    private final AssetImageUploadJobRunner runner;
    private final MediaGeneratedImageClient mediaGeneratedImageClient;
    private final MediaSettingsBridgeClient mediaSettingsBridgeClient;
    private final CurrentActorService currentActorService;
    private final ObjectMapper objectMapper;

    public AssetImageUploadJobStarter(
            GenerationJobClient generationJobClient,
            AssetImageUploadJobRunner runner,
            MediaGeneratedImageClient mediaGeneratedImageClient,
            MediaSettingsBridgeClient mediaSettingsBridgeClient,
            CurrentActorService currentActorService,
            ObjectMapper objectMapper) {
        this.generationJobClient = generationJobClient;
        this.runner = runner;
        this.mediaGeneratedImageClient = mediaGeneratedImageClient;
        this.mediaSettingsBridgeClient = mediaSettingsBridgeClient;
        this.currentActorService = currentActorService;
        this.objectMapper = objectMapper;
    }

    /** <b>リクエストスレッドで</b>呼ぶこと。画像が取れなければジョブを作らず例外を伝える。 */
    public GenerationJobSummary start(Long projectId, Long generatedImageId) {
        byte[] data = mediaGeneratedImageClient.fetchImageFile(generatedImageId);
        AssetImageFormat format = AssetImageFormat.detect(data);
        String filename = "comfyui-" + generatedImageId + "." + format.extension();
        int longEdgePx = mediaSettingsBridgeClient.resolveArticleImageLongEdgePx(projectId);
        Long actorId = currentActorService.getCurrentActorId();
        String authorization = currentActorService.getAuthorizationHeader();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", projectId);
        payload.put("generatedImageId", generatedImageId);
        GenerationJobSummary job = generationJobClient.create(JOB_TYPE, toJson(payload), authorization);
        try {
            runner.run(job.id(), projectId, data, filename, format.mimeType(), longEdgePx, actorId);
        } catch (TaskRejectedException e) {
            generationJobClient.updateStatus(job.id(), "failed", toJson(Map.of(
                    "error", "アセット画像のアップロードの待ち行列が満杯です。しばらくしてからもう一度要求してください",
                    "errorType", AssetImageUploadJobRunner.ERROR_QUEUE_FULL)));
            return new GenerationJobSummary(job.id(), job.type(), "failed", job.createdAt(), job.updatedAt());
        }
        return job;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
