package com.letsblog.publishing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.publishing.domain.BulkOperationLog;
import com.letsblog.publishing.dto.BulkOperationLogResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * アセット画像アップロードジョブ(issue #1764)の実行本体。受理側の{@link AssetImageUploadJobStarter}とは
 * {@code @Async}の自己呼び出し問題のため別クラス。環境ごとの失敗(画素数の上限による拒否を含む)は
 * {@code result_payload.logs}の{@code FAILED}で表し、ジョブは{@code done}にする。ジョブを{@code failed}にするのは
 * 操作全体の失敗だけ。このスレッドにはHTTPリクエストが無いので、利用者のBearerを要するクライアントは持ち込まない。
 */
@Service
public class AssetImageUploadJobRunner {

    static final String ERROR_PROJECT_NOT_FOUND = "project_not_found";
    static final String ERROR_UNEXPECTED = "unexpected_error";
    static final String ERROR_QUEUE_FULL = "queue_full";

    private static final Logger log = LoggerFactory.getLogger(AssetImageUploadJobRunner.class);

    private final BulkManagementService bulkManagementService;
    private final GenerationJobClient generationJobClient;
    private final ObjectMapper objectMapper;

    public AssetImageUploadJobRunner(
            BulkManagementService bulkManagementService,
            GenerationJobClient generationJobClient,
            ObjectMapper objectMapper) {
        this.bulkManagementService = bulkManagementService;
        this.generationJobClient = generationJobClient;
        this.objectMapper = objectMapper;
    }

    @Async("assetImageUploadExecutor")
    public void run(Long jobId, Long projectId, byte[] data, String filename, String contentType,
                    int longEdgePx, Long actorId) {
        try {
            generationJobClient.updateStatus(jobId, "running", toJson(Map.of("phase", "uploading")));
            List<BulkOperationLog> logs = bulkManagementService.uploadImageToAllEnvironments(
                    projectId, data, filename, contentType, longEdgePx, actorId);
            generationJobClient.updateStatus(jobId, "done", toJson(donePayload(projectId, logs)));
        } catch (Exception e) {
            log.warn("Asset image upload job {} failed", jobId, e);
            generationJobClient.updateStatus(jobId, "failed", toJson(failurePayload(e)));
        }
    }

    private static Map<String, Object> donePayload(Long projectId, List<BulkOperationLog> logs) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("phase", "done");
        result.put("projectId", projectId);
        List<Map<String, Object>> entries = new ArrayList<>(logs.size());
        for (BulkOperationLog entry : logs) {
            entries.add(toEntry(BulkOperationLogResponse.from(entry)));
        }
        result.put("logs", entries);
        return result;
    }

    /** {@link BulkOperationLogResponse}と同じ形。日時はRFC 3339風の文字列で載せる。 */
    private static Map<String, Object> toEntry(BulkOperationLogResponse r) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("operationType", r.operationType());
        entry.put("sourceType", r.sourceType());
        entry.put("value", r.value());
        entry.put("categorySlug", r.categorySlug());
        entry.put("categoryParentSlug", r.categoryParentSlug());
        entry.put("categoryTargetSlug", r.categoryTargetSlug());
        entry.put("categoryDescription", r.categoryDescription());
        entry.put("originalFilename", r.originalFilename());
        entry.put("postStatus", r.postStatus());
        entry.put("environment", r.environment());
        entry.put("status", r.status());
        entry.put("level", r.level());
        entry.put("errorMessage", r.errorMessage());
        entry.put("stackTrace", r.stackTrace());
        entry.put("createdAt", Objects.toString(r.createdAt(), null));
        return entry;
    }

    private static Map<String, Object> failurePayload(Exception e) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", Objects.toString(e.getMessage(), e.getClass().getSimpleName()));
        result.put("errorType", e instanceof ProjectNotFoundException ? ERROR_PROJECT_NOT_FOUND : ERROR_UNEXPECTED);
        return result;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
