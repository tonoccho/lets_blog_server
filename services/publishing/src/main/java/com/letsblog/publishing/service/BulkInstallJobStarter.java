package com.letsblog.publishing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.common.client.GenerationJobSummary;
import com.letsblog.publishing.domain.BulkOperationType;
import com.letsblog.publishing.dto.ApplyToAllEnvironmentsRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 全環境インストール(slug / zip)を非同期ジョブとして受理する(issue #1702)。{@code generation_jobs}にジョブを作り、
 * ジョブIDを返して即座に戻る。インストール本体は別クラスの{@link BulkInstallJobRunner}({@code @Async})が担う
 * ({@code @Async}は同一クラス内の自己呼び出しに効かないため分けている。project-serviceの
 * {@code ProjectEnvironmentSyncJobStarter}と同型)。
 *
 * <p>ジョブの{@code type}は{@link #SLUG_JOB_TYPE} / {@link #ZIP_JOB_TYPE}。非AIの名前で、キューUIと統合操作ログが
 * 種別から遷移先・分類を決めるので変えない。
 */
@Service
public class BulkInstallJobStarter {

    /** slugでの全環境インストールのジョブ種別。キューUIの遷移先の判定と統合操作ログの分類に使われるので変えないこと。 */
    public static final String SLUG_JOB_TYPE = "bulk_slug_install";

    /** zipでの全環境インストールのジョブ種別。同上。 */
    public static final String ZIP_JOB_TYPE = "bulk_zip_install";

    private final GenerationJobClient generationJobClient;
    private final BulkInstallJobRunner runner;
    private final BulkManagementService bulkManagementService;
    private final BulkUploadStorageService bulkUploadStorageService;
    private final CurrentActorService currentActorService;
    private final ObjectMapper objectMapper;

    public BulkInstallJobStarter(
            GenerationJobClient generationJobClient,
            BulkInstallJobRunner runner,
            BulkManagementService bulkManagementService,
            BulkUploadStorageService bulkUploadStorageService,
            CurrentActorService currentActorService,
            ObjectMapper objectMapper) {
        this.generationJobClient = generationJobClient;
        this.runner = runner;
        this.bulkManagementService = bulkManagementService;
        this.bulkUploadStorageService = bulkUploadStorageService;
        this.currentActorService = currentActorService;
        this.objectMapper = objectMapper;
    }

    /**
     * <b>リクエストスレッドで</b>呼ぶこと。入力の検証と操作者の解決をここで行い(誤っていればジョブを作らず例外にする)、
     * ランナーへは操作者のIDだけを渡す。利用者のBearerは持ち込まない(5分で切れる。ランナーのサービス間呼び出しは
     * サービス自身のClient Credentialsを使う)。
     */
    public GenerationJobSummary startSlugInstall(Long projectId, ApplyToAllEnvironmentsRequest request) {
        bulkManagementService.validateSlugInstall(request.operationType(), request.value());
        Long actorId = currentActorService.getCurrentActorId();
        String authorization = currentActorService.getAuthorizationHeader();
        Map<String, Object> payload = basePayload(projectId, request.operationType());
        payload.put("value", request.value());
        GenerationJobSummary job = generationJobClient.create(SLUG_JOB_TYPE, toJson(payload), authorization);
        try {
            runner.runSlugInstall(job.id(), projectId, request.operationType(), request.value(), actorId);
        } catch (TaskRejectedException e) {
            return queueFull(job);
        }
        return job;
    }

    /**
     * <b>リクエストスレッドで</b>呼ぶこと。zip本体はリクエストの終了後には読めないので、受理スレッドで既存の
     * {@link BulkUploadStorageService#store}へ保存し、ランナーには保存先だけを渡す(新しい保存場所や削除の仕組みは作らない)。
     */
    public GenerationJobSummary startZipInstall(Long projectId, BulkOperationType type, MultipartFile file)
            throws IOException {
        bulkManagementService.validateZipInstall(type, file);
        Long actorId = currentActorService.getCurrentActorId();
        String authorization = currentActorService.getAuthorizationHeader();
        BulkUploadStorageService.StoredZip stored =
                bulkUploadStorageService.store(projectId, file.getBytes(), file.getOriginalFilename());
        Map<String, Object> payload = basePayload(projectId, type);
        payload.put("storagePath", stored.storagePath());
        payload.put("sha256", stored.sha256());
        payload.put("originalFilename", stored.originalFilename());
        GenerationJobSummary job = generationJobClient.create(ZIP_JOB_TYPE, toJson(payload), authorization);
        try {
            runner.runZipInstall(job.id(), projectId, type, stored, actorId);
        } catch (TaskRejectedException e) {
            return queueFull(job);
        }
        return job;
    }

    /** 実行枠と待ち行列が満杯。runningのまま取り残さず、理由が読めるfailedにする。 */
    private GenerationJobSummary queueFull(GenerationJobSummary job) {
        generationJobClient.updateStatus(job.id(), "failed", toJson(Map.of(
                "error", "全環境インストールの待ち行列が満杯です。しばらくしてからもう一度要求してください",
                "errorType", BulkInstallJobRunner.ERROR_QUEUE_FULL)));
        return new GenerationJobSummary(job.id(), job.type(), "failed", job.createdAt(), job.updatedAt());
    }

    /** ジョブの要求内容。キューUIは{@code projectId}から結果(メンテナンスタブ)の遷移先を決める。 */
    private static Map<String, Object> basePayload(Long projectId, BulkOperationType type) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", projectId);
        payload.put("operationType", type.name());
        return payload;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
