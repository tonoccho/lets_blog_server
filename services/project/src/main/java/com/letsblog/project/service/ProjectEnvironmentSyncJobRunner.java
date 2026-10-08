package com.letsblog.project.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.project.client.BearerScope;
import com.letsblog.project.dto.SyncEnvironmentRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 環境間同期ジョブ(issue #1697)の実行本体。受理側の{@link ProjectEnvironmentSyncJobStarter}とは{@code @Async}の
 * 自己呼び出し問題のため別クラス。進捗・完了・失敗は{@link GenerationJobClient#updateStatus}
 * (サービス自身のClient Credentials。#1083)でジョブへ反映する。同期のトランザクションは
 * {@link ProjectEnvironmentSyncService#sync}自身の{@code @Transactional}が別Beanなので効く。
 *
 * <p>失敗の{@code errorType}: {@value #ERROR_PROJECT_NOT_FOUND} / {@value #ERROR_INVALID_REQUEST} /
 * {@value #ERROR_SYNC_FAILED} / {@value #ERROR_UNEXPECTED}。
 */
@Service
public class ProjectEnvironmentSyncJobRunner {

    static final String ERROR_PROJECT_NOT_FOUND = "project_not_found";
    static final String ERROR_INVALID_REQUEST = "invalid_request";
    static final String ERROR_SYNC_FAILED = "sync_failed";
    static final String ERROR_UNEXPECTED = "unexpected_error";

    private static final Logger log = LoggerFactory.getLogger(ProjectEnvironmentSyncJobRunner.class);

    private final ProjectEnvironmentSyncService syncService;
    private final CurrentActorService currentActorService;
    private final GenerationJobClient generationJobClient;
    private final ObjectMapper objectMapper;

    public ProjectEnvironmentSyncJobRunner(
            ProjectEnvironmentSyncService syncService,
            CurrentActorService currentActorService,
            GenerationJobClient generationJobClient,
            ObjectMapper objectMapper) {
        this.syncService = syncService;
        this.currentActorService = currentActorService;
        this.generationJobClient = generationJobClient;
        this.objectMapper = objectMapper;
    }

    /** @param actor 受理側がリクエストスレッドで解決した操作者。監査ログとサービス間呼び出しのBearerに使う。 */
    @Async("environmentSyncExecutor")
    public void run(Long jobId, Long projectId, SyncEnvironmentRequest request, ActorSnapshot actor) {
        try {
            generationJobClient.updateStatus(jobId, "running", toJson(JobProgressPayload.phase("syncing")));
            currentActorService.runAs(actor, () ->
                    // リクエストの無いスレッドなので、サービス間ブリッジが使うBearerを取り置いたものから渡す(#1558と同じ仕組み)。
                    BearerScope.call(actor.authorization(), () -> {
                        syncService.sync(projectId, request.from(), request.to(), request.targets());
                        return null;
                    }));
            generationJobClient.updateStatus(jobId, "done", toJson(donePayload(projectId, request)));
        } catch (RuntimeException e) {
            log.warn("Environment sync job {} failed", jobId, e);
            generationJobClient.updateStatus(jobId, "failed", toJson(failurePayload(e)));
        }
    }

    private static Map<String, Object> donePayload(Long projectId, SyncEnvironmentRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("phase", "done");
        result.put("projectId", projectId);
        result.put("from", request.from());
        result.put("to", request.to());
        result.put("targets", request.targets());
        return result;
    }

    static Map<String, Object> failurePayload(RuntimeException e) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        result.put("errorType", classify(e));
        return result;
    }

    private static String classify(RuntimeException e) {
        if (e instanceof ProjectNotFoundException) {
            return ERROR_PROJECT_NOT_FOUND;
        }
        if (e instanceof IllegalArgumentException || e instanceof SiteNotFoundException) {
            return ERROR_INVALID_REQUEST;
        }
        if (e instanceof ProvisioningException) {
            return ERROR_SYNC_FAILED;
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
}
