package com.letsblog.publishing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.common.job.JobCompletionLog;
import com.letsblog.publishing.domain.BulkOperationLog;
import com.letsblog.publishing.domain.BulkOperationType;
import com.letsblog.publishing.dto.BulkOperationLogResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 全環境インストールジョブ(issue #1702)の実行本体。受理側の{@link BulkInstallJobStarter}とは{@code @Async}の
 * 自己呼び出し問題のため別クラス。進捗・完了・失敗は{@link GenerationJobClient#updateStatus}
 * (サービス自身のClient Credentials。#1083)でジョブへ反映する。
 *
 * <p>環境ごとの失敗は{@code result_payload.logs}の{@code FAILED}で表し、ジョブは{@code done}にする(1環境の失敗が
 * 他環境を止めないことと、環境の並び{@code ENVIRONMENT_ORDER}は{@code EnvironmentTasks.runAll}が保証する)。
 * ジョブを{@code failed}にするのは操作全体の失敗だけ: {@value #ERROR_PROJECT_NOT_FOUND} / {@value #ERROR_INVALID_REQUEST} /
 * {@value #ERROR_UNEXPECTED}(と受理側が付ける{@value #ERROR_QUEUE_FULL})。
 *
 * <p>このスレッドにはHTTPリクエストが無いので、project-serviceへの呼び出し({@code ProjectServiceClient})は
 * サービス自身のClient Credentialsを使う({@code OutboundAuthHeaders})。利用者のBearerは持ち込まない。
 */
@Service
public class BulkInstallJobRunner {

    static final String ERROR_PROJECT_NOT_FOUND = "project_not_found";
    static final String ERROR_INVALID_REQUEST = "invalid_request";
    static final String ERROR_UNEXPECTED = "unexpected_error";
    static final String ERROR_QUEUE_FULL = "queue_full";

    private static final String JOB_KIND = "bulk_install";

    private static final Logger log = LoggerFactory.getLogger(BulkInstallJobRunner.class);

    private final BulkManagementService bulkManagementService;
    private final BulkUploadStorageService bulkUploadStorageService;
    private final GenerationJobClient generationJobClient;
    private final ObjectMapper objectMapper;

    public BulkInstallJobRunner(
            BulkManagementService bulkManagementService,
            BulkUploadStorageService bulkUploadStorageService,
            GenerationJobClient generationJobClient,
            ObjectMapper objectMapper) {
        this.bulkManagementService = bulkManagementService;
        this.bulkUploadStorageService = bulkUploadStorageService;
        this.generationJobClient = generationJobClient;
        this.objectMapper = objectMapper;
    }

    /** @param actorId 受理側がリクエストスレッドで解決した操作者のID(結果のログに載る。Bearerは持ち込まない)。 */
    @Async("bulkInstallExecutor")
    public void runSlugInstall(Long jobId, Long projectId, BulkOperationType type, String value, Long actorId) {
        run(jobId, projectId, () -> bulkManagementService.applyToAllEnvironments(projectId, type, value, actorId));
    }

    /** @param stored 受理側が保存したzip。ランナーは保存先から読む(新しい保存場所は作らない)。 */
    @Async("bulkInstallExecutor")
    public void runZipInstall(
            Long jobId, Long projectId, BulkOperationType type, BulkUploadStorageService.StoredZip stored, Long actorId) {
        run(jobId, projectId, () -> {
            byte[] bytes = bulkUploadStorageService.load(stored.storagePath());
            return bulkManagementService.applyStoredZipToAllEnvironments(projectId, type, bytes, stored, actorId);
        });
    }

    private void run(Long jobId, Long projectId, InstallTask task) {
        var timer = JobCompletionLog.start(log, JOB_KIND, jobId);
        try {
            generationJobClient.updateStatus(jobId, "running", toJson(Map.of("phase", "installing")));
            List<BulkOperationLog> logs = task.run();
            generationJobClient.updateStatus(jobId, "done", toJson(donePayload(projectId, logs)));
            timer.success();
        } catch (Exception e) {
            timer.failure(e);
            log.warn("Bulk install job {} failed", jobId, e);
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

    /** {@link BulkOperationLogResponse}と同じ形。日時はZ終端のRFC 3339文字列で載せる(JSON化にjava.timeのモジュールを要らなくする)。 */
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
        entry.put("createdAt", r.createdAt() == null ? null : r.createdAt().toString());
        return entry;
    }

    static Map<String, Object> failurePayload(Exception e) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        result.put("errorType", classify(e));
        return result;
    }

    private static String classify(Exception e) {
        if (e instanceof ProjectNotFoundException) {
            return ERROR_PROJECT_NOT_FOUND;
        }
        if (e instanceof IllegalArgumentException) {
            return ERROR_INVALID_REQUEST;
        }
        return ERROR_UNEXPECTED;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    @FunctionalInterface
    private interface InstallTask {
        List<BulkOperationLog> run() throws Exception;
    }
}
