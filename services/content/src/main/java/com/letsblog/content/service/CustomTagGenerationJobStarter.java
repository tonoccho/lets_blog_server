package com.letsblog.content.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.common.client.GenerationJobSummary;
import com.letsblog.content.dto.GenerateCustomTagRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

/**
 * カスタムタグのAI生成を非同期ジョブとして受理する(issue #1409)。管理者であることを確かめて
 * {@code generation_jobs}にジョブを作り、ジョブIDを返して即座に戻る。生成本体は別クラスの
 * {@link CustomTagGenerationJobRunner}({@code @Async})が担う(project-serviceの
 * {@code ManagedSiteProvisioningJobStarter}/{@code Runner}、media-serviceの画像生成と同型。
 * {@code @Async}は同一クラス内の自己呼び出しに効かないため分けている)。
 *
 * <p>ジョブの{@code type}は{@link #JOB_TYPE}。処理キューUIが種別から表示名と「結果を見る」の
 * 遷移先を決める(#1407)ので変えない。
 */
@Service
public class CustomTagGenerationJobStarter {

    /** カスタムタグ生成ジョブの種別。処理キューの表示名・遷移先の判定に使われるので変えないこと。 */
    public static final String JOB_TYPE = "custom_tag_generation";

    private final GenerationJobClient generationJobClient;
    private final CustomTagGenerationJobRunner runner;
    private final AdminAuthorizationService adminAuthorizationService;
    private final ObjectMapper objectMapper;

    public CustomTagGenerationJobStarter(
            GenerationJobClient generationJobClient,
            CustomTagGenerationJobRunner runner,
            AdminAuthorizationService adminAuthorizationService,
            ObjectMapper objectMapper) {
        this.generationJobClient = generationJobClient;
        this.runner = runner;
        this.adminAuthorizationService = adminAuthorizationService;
        this.objectMapper = objectMapper;
    }

    /**
     * <b>リクエストスレッドで</b>呼ぶこと。
     *
     * @param bearerToken 呼び出し元の{@code Authorization}ヘッダー値。ジョブ作成にだけ使う
     *                    (以降の更新・ai-service呼び出しはサービス自身のトークン。#1083)。
     */
    public GenerationJobSummary start(GenerateCustomTagRequest request, String bearerToken) {
        adminAuthorizationService.requireAdmin();
        GenerationJobSummary job = generationJobClient.create(JOB_TYPE, requestPayload(request), bearerToken);
        try {
            runner.run(job.id(), request);
        } catch (TaskRejectedException e) {
            // 実行枠と待ち行列が満杯。ジョブを作ってしまっているので、runningのまま取り残さず、
            // 理由が読めるfailedにする。応答も実際の状態(failed)を返す。
            generationJobClient.updateStatus(job.id(), "failed", toJson(Map.of(
                    "error", "カスタムタグ生成の待ち行列が満杯です。しばらくしてからもう一度要求してください",
                    "errorType", "queue_full")));
            return new GenerationJobSummary(job.id(), job.type(), "failed", job.createdAt(), job.updatedAt());
        }
        return job;
    }

    /** ジョブの要求内容。処理キューが「結果を見る」の遷移先(プロジェクト)を決めるのに projectId を使う。 */
    private String requestPayload(GenerateCustomTagRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tagName", request.tagName());
        payload.put("description", request.description());
        payload.put("projectId", request.projectId());
        payload.put("prompt", request.prompt());
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
