package com.letsblog.project.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.common.client.GenerationJobSummary;
import com.letsblog.project.dto.SyncEnvironmentRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

/**
 * 環境間同期を非同期ジョブとして受理する(issue #1697)。{@code generation_jobs}にジョブを作り、
 * ジョブIDを返して即座に戻る。同期本体は別クラスの{@link ProjectEnvironmentSyncJobRunner}({@code @Async})が担う
 * ({@code @Async}は同一クラス内の自己呼び出しに効かないため分けている。{@link ManagedSiteProvisioningJobStarter}と同型)。
 *
 * <p>ジョブの{@code type}は{@link #JOB_TYPE}。非AIの名前で、キューUIが種別から遷移先を決める(#1407)ため変えない。
 */
@Service
public class ProjectEnvironmentSyncJobStarter {

    /** 環境間同期ジョブの種別。キューUIの遷移先の判定と統合操作ログの分類に使われるので変えないこと。 */
    public static final String JOB_TYPE = "environment_sync";

    private final GenerationJobClient generationJobClient;
    private final ProjectEnvironmentSyncJobRunner runner;
    private final CurrentActorService currentActorService;
    private final ObjectMapper objectMapper;

    public ProjectEnvironmentSyncJobStarter(
            GenerationJobClient generationJobClient,
            ProjectEnvironmentSyncJobRunner runner,
            CurrentActorService currentActorService,
            ObjectMapper objectMapper) {
        this.generationJobClient = generationJobClient;
        this.runner = runner;
        this.currentActorService = currentActorService;
        this.objectMapper = objectMapper;
    }

    /** <b>リクエストスレッドで</b>呼ぶこと。操作者をここで解決してランナーへ渡す(#1405、#1479と同じ)。 */
    public GenerationJobSummary start(Long projectId, SyncEnvironmentRequest request) {
        ActorSnapshot actor = currentActorService.snapshot();
        GenerationJobSummary job = generationJobClient.create(JOB_TYPE, requestPayload(projectId, request), actor.authorization());
        try {
            runner.run(job.id(), projectId, request, actor);
        } catch (TaskRejectedException e) {
            // 実行枠と待ち行列が満杯。runningのまま取り残さず、理由が読めるfailedにする。
            generationJobClient.updateStatus(job.id(), "failed", toJson(Map.of(
                    "error", "環境間同期の待ち行列が満杯です。しばらくしてからもう一度要求してください",
                    "errorType", "queue_full")));
            return new GenerationJobSummary(job.id(), job.type(), "failed", job.createdAt(), job.updatedAt());
        }
        return job;
    }

    /** ジョブの要求内容。キューUIは{@code projectId}から結果(プロジェクトの設定タブ)の遷移先を決める。 */
    private String requestPayload(Long projectId, SyncEnvironmentRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", projectId);
        payload.put("from", request.from());
        payload.put("to", request.to());
        payload.put("targets", request.targets());
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
