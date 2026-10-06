package com.letsblog.project.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.common.client.GenerationJobSummary;
import com.letsblog.project.domain.EmbedTagType;
import com.letsblog.project.domain.StaticContentType;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

/**
 * 静的コンテンツ・タグデザインのAI生成を非同期ジョブとして受理する(issue #1409)。{@code generation_jobs}に
 * ジョブを作り、ジョブIDを返して即座に戻る。生成本体は別クラスの{@link TextGenerationJobRunner}
 * ({@code @Async})が担う({@code ManagedSiteProvisioningJobStarter}/{@code Runner}と同型。{@code @Async}は
 * 同一クラス内の自己呼び出しに効かないため分けている)。
 *
 * <p>認可(管理者・プロジェクトメンバー)は呼び出し側のコントローラが済ませてから呼ぶ。
 * ジョブの{@code type}は{@link #JOB_TYPE_STATIC_CONTENT} / {@link #JOB_TYPE_TAG_DESIGN}。処理キューUIが
 * 種別から表示名と「結果を見る」の遷移先を決める(#1407)ので変えない。ジョブの要求内容には、遷移先を決める
 * {@code siteId}・{@code projectId}(グローバルは{@code null})を残す。
 */
@Service
public class TextGenerationJobStarter {

    /** 静的コンテンツ生成ジョブの種別。処理キューの表示名・遷移先の判定に使われるので変えないこと。 */
    public static final String JOB_TYPE_STATIC_CONTENT = "static_content_generation";
    /** タグデザイン生成ジョブの種別。処理キューの表示名・遷移先の判定に使われるので変えないこと。 */
    public static final String JOB_TYPE_TAG_DESIGN = "tag_design_generation";

    private final GenerationJobClient generationJobClient;
    private final TextGenerationJobRunner runner;
    private final StaticContentGenerationService staticContentGenerationService;
    private final CurrentActorService currentActorService;
    private final ObjectMapper objectMapper;

    public TextGenerationJobStarter(
            GenerationJobClient generationJobClient,
            TextGenerationJobRunner runner,
            StaticContentGenerationService staticContentGenerationService,
            CurrentActorService currentActorService,
            ObjectMapper objectMapper) {
        this.generationJobClient = generationJobClient;
        this.runner = runner;
        this.staticContentGenerationService = staticContentGenerationService;
        this.currentActorService = currentActorService;
        this.objectMapper = objectMapper;
    }

    /** <b>リクエストスレッドで</b>呼ぶこと。サイトが無ければジョブを作らず{@link SiteNotFoundException}。 */
    public GenerationJobSummary startStaticContent(Long siteId, StaticContentType contentType) {
        staticContentGenerationService.requireSite(siteId);
        String bearer = currentActorService.getAuthorizationHeader();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("siteId", siteId);
        payload.put("contentType", contentType.name());
        GenerationJobSummary job = generationJobClient.create(JOB_TYPE_STATIC_CONTENT, toJson(payload), bearer);
        try {
            // プラグイン取得のブリッジ呼び出しが使うBearerを、リクエストの無いジョブのスレッドへ取り置いて渡す(#1558と同じ)。
            runner.runStaticContent(job.id(), siteId, contentType, bearer);
        } catch (TaskRejectedException e) {
            return failQueueFull(job, "静的コンテンツ生成");
        }
        return job;
    }

    /** <b>リクエストスレッドで</b>呼ぶこと。{@code projectId}が{@code null}ならグローバル既定のタグデザイン。 */
    public GenerationJobSummary startTagDesign(Long projectId, EmbedTagType tagType, String prompt) {
        String bearer = currentActorService.getAuthorizationHeader();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", projectId);
        payload.put("tagType", tagType.name());
        payload.put("prompt", prompt);
        GenerationJobSummary job = generationJobClient.create(JOB_TYPE_TAG_DESIGN, toJson(payload), bearer);
        try {
            runner.runTagDesign(job.id(), projectId, tagType, prompt);
        } catch (TaskRejectedException e) {
            return failQueueFull(job, "タグデザイン生成");
        }
        return job;
    }

    /**
     * 実行枠と待ち行列が満杯。ジョブを作ってしまっているので、runningのまま取り残さず、理由が読めるfailedに
     * する。応答も実際の状態(failed)を返す。
     */
    private GenerationJobSummary failQueueFull(GenerationJobSummary job, String what) {
        generationJobClient.updateStatus(job.id(), "failed", toJson(Map.of(
                "error", what + "の待ち行列が満杯です。しばらくしてからもう一度要求してください",
                "errorType", "queue_full")));
        return new GenerationJobSummary(job.id(), job.type(), "failed", job.createdAt(), job.updatedAt());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
