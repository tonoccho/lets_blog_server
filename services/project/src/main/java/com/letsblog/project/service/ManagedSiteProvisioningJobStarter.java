package com.letsblog.project.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.common.client.GenerationJobSummary;
import com.letsblog.project.dto.CreateManagedWordPressSiteRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

/**
 * サイト自動構築を非同期ジョブとして受理する(issue #1479)。{@code generation_jobs}にジョブを作り、
 * ジョブIDを返して即座に戻る。構築本体は別クラスの{@link ManagedSiteProvisioningJobRunner}
 * ({@code @Async})が担う(画像生成の{@code ImageGenerationJobStarter}/{@code Runner}、#1405と同型。
 * {@code @Async}は同一クラス内の自己呼び出しに効かないため分けている)。
 *
 * <p>ジョブの{@code type}は{@link #JOB_TYPE}。既存の{@code <対象>_<操作>}の慣例
 * ({@code comfyui_checkpoint_download}/{@code image_generation})に倣った非AIの名前で、
 * キューUIが種別から遷移先を決める(#1407)ため変えない。
 */
@Service
public class ManagedSiteProvisioningJobStarter {

    /** サイト自動構築ジョブの種別。キューUIの遷移先の判定に使われるので変えないこと。 */
    public static final String JOB_TYPE = "site_provisioning";

    private final GenerationJobClient generationJobClient;
    private final ManagedSiteProvisioningJobRunner runner;
    private final CurrentActorService currentActorService;
    private final ObjectMapper objectMapper;
    private final JobHeartbeatTracker heartbeatTracker;

    public ManagedSiteProvisioningJobStarter(
            GenerationJobClient generationJobClient,
            ManagedSiteProvisioningJobRunner runner,
            CurrentActorService currentActorService,
            ObjectMapper objectMapper,
            JobHeartbeatTracker heartbeatTracker) {
        this.generationJobClient = generationJobClient;
        this.runner = runner;
        this.currentActorService = currentActorService;
        this.objectMapper = objectMapper;
        this.heartbeatTracker = heartbeatTracker;
    }

    /**
     * <b>リクエストスレッドで</b>呼ぶこと。操作者をここで解決してランナーへ渡す
     * (ジョブのスレッドにはリクエストもセキュリティコンテキストも無く、監査ログの操作者が失われる。#1405)。
     */
    public GenerationJobSummary start(CreateManagedWordPressSiteRequest request) {
        ActorSnapshot actor = currentActorService.snapshot();
        GenerationJobSummary job = generationJobClient.create(JOB_TYPE, requestPayload(request), actor.authorization());
        // 順番待ちの間も滞留回収されないよう、ランナーへ渡す前から追跡する(#1724)。
        heartbeatTracker.track(job.id());
        try {
            runner.run(job.id(), request, actor);
        } catch (TaskRejectedException e) {
            // 実行枠と待ち行列が満杯。ジョブを作ってしまっているので、runningのまま取り残さず、
            // 理由が読めるfailedにする。応答も実際の状態(failed)を返す。
            heartbeatTracker.complete(job.id(), () -> generationJobClient.updateStatus(job.id(), "failed", toJson(Map.of(
                    "error", "サイト構築の待ち行列が満杯です。しばらくしてからもう一度要求してください",
                    "errorType", "queue_full"))));
            return new GenerationJobSummary(job.id(), job.type(), "failed", job.createdAt(), job.updatedAt());
        }
        return job;
    }

    /** ジョブの要求内容。管理者パスワードとメールは、ジョブ一覧を読める人へ見せないため残さない。 */
    private String requestPayload(CreateManagedWordPressSiteRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", request.name());
        payload.put("siteKey", request.siteKey());
        payload.put("title", request.title());
        payload.put("locale", request.locale());
        payload.put("templateSiteId", request.templateSiteId());
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
