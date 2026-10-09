package com.letsblog.project.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.project.dto.CreateManagedWordPressSiteRequest;
import com.letsblog.project.dto.SiteResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * サイト自動構築ジョブ(issue #1479)の実行本体。受理側の{@link ManagedSiteProvisioningJobStarter}とは
 * {@code @Async}の自己呼び出し問題のため別クラス。進捗・完了・失敗は{@link GenerationJobClient#updateStatus}
 * (サービス自身のClient Credentials。#1083)でジョブへ反映するので、呼び出し元ユーザーのBearerは持たない。
 * 構築自体は最大240秒だが、実行枠1・待ち行列5で前のジョブを待つ間はupdated_atが進まず滞留回収(既定15分)に
 * かかりうるので、受理側が追跡を始め、{@link JobHeartbeatTracker}が生きている間ハートビートを打つ(#1724)。
 * 終端の書き込みは{@link JobHeartbeatTracker#complete}を通し、ハートビートがdone/failedをrunningへ戻さないようにする。
 *
 * <p>進行段階は{@code provisioning} → {@code registering} → {@code done}。provision-agentの
 * {@code /provision}は1回のPOSTで完結し内部の進み具合を返さないので、この粒度が上限である。
 * 失敗には利用者が読める{@code error}と原因を区別できる{@code errorType}を残す:
 * {@value #ERROR_DUPLICATE_SITE_KEY} / {@value #ERROR_ALREADY_PROVISIONED} /
 * {@value #ERROR_PROVISIONING_FAILED} / {@value #ERROR_INVALID_REQUEST} / {@value #ERROR_UNEXPECTED}。
 * 構築失敗のときのdeprovisionによる後始末は{@link WordPressSiteProvisioningService}が行う。
 */
@Service
public class ManagedSiteProvisioningJobRunner {

    static final String ERROR_DUPLICATE_SITE_KEY = "duplicate_site_key";
    static final String ERROR_ALREADY_PROVISIONED = "already_provisioned";
    static final String ERROR_PROVISIONING_FAILED = "provisioning_failed";
    static final String ERROR_INVALID_REQUEST = "invalid_request";
    static final String ERROR_UNEXPECTED = "unexpected_error";

    private static final Logger log = LoggerFactory.getLogger(ManagedSiteProvisioningJobRunner.class);

    private final WordPressSiteProvisioningService provisioningService;
    private final CurrentActorService currentActorService;
    private final GenerationJobClient generationJobClient;
    private final ObjectMapper objectMapper;
    private final JobHeartbeatTracker heartbeatTracker;

    public ManagedSiteProvisioningJobRunner(
            WordPressSiteProvisioningService provisioningService,
            CurrentActorService currentActorService,
            GenerationJobClient generationJobClient,
            ObjectMapper objectMapper,
            JobHeartbeatTracker heartbeatTracker) {
        this.provisioningService = provisioningService;
        this.currentActorService = currentActorService;
        this.generationJobClient = generationJobClient;
        this.objectMapper = objectMapper;
        this.heartbeatTracker = heartbeatTracker;
    }

    /** @param actor 受理側がリクエストスレッドで解決した操作者。監査ログとサイト登録の著者解決に使う。 */
    @Async("siteProvisioningExecutor")
    public void run(Long jobId, CreateManagedWordPressSiteRequest request, ActorSnapshot actor) {
        try {
            // 取り置いた利用者のBearerは5分で切れるので持ち込まない。サービス間ブリッジは、リクエストの無い
            // このスレッドではサービス自身のClient Credentialsを呼び出しの都度使う(#1723)。
            SiteResponse site = currentActorService.runAs(actor.withoutAuthorization(), () ->
                    provisioningService.createManagedSiteForJob(request, phase -> reportPhase(jobId, phase)));
            heartbeatTracker.complete(jobId, () -> generationJobClient.updateStatus(jobId, "done", toJson(donePayload(site))));
        } catch (RuntimeException e) {
            log.warn("Site provisioning job {} failed", jobId, e);
            heartbeatTracker.complete(jobId, () -> generationJobClient.updateStatus(jobId, "failed", toJson(failurePayload(e))));
        }
    }

    private void reportPhase(Long jobId, String phase) {
        heartbeatTracker.updatePhase(jobId, phase);
        generationJobClient.updateStatus(jobId, "running", toJson(JobProgressPayload.phase(phase)));
    }

    private static Map<String, Object> donePayload(SiteResponse site) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("phase", "done");
        result.put("siteId", site.id());
        result.put("siteKey", site.siteKey());
        result.put("name", site.name());
        result.put("baseUrl", site.baseUrl());
        return result;
    }

    static Map<String, Object> failurePayload(RuntimeException e) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        result.put("errorType", classify(e));
        return result;
    }

    private static String classify(RuntimeException e) {
        if (e instanceof DuplicateSiteKeyException) {
            return ERROR_DUPLICATE_SITE_KEY;
        }
        if (e instanceof SiteAlreadyProvisionedException) {
            return ERROR_ALREADY_PROVISIONED;
        }
        if (e instanceof ProvisioningException) {
            return ERROR_PROVISIONING_FAILED;
        }
        if (e instanceof IllegalArgumentException || e instanceof SiteNotFoundException) {
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
}
