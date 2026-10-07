package com.letsblog.media.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.media.client.CmsBridgeClient;
import com.letsblog.common.client.GenerationJobClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * legacy-apiから移設(issue #573 stage3)。メディアガベージコレクションの一括削除を
 * バックグラウンドスレッドで実行する(issue #500)。Spring の{@code @Async}は同一クラス内の
 * 自己呼び出しには効かない(プロキシを経由しないため)ため、ジョブを起動する
 * {@link MediaGarbageCollectionService}とは別クラスに分離している({@link ModelInstallJobRunner}と
 * 同じ構成)。
 *
 * <p>元の実装は{@code Site}/{@code CmsAdapter}/{@code GenerationJobRepository}へ直接アクセスして
 * いたが、これらはこのissueの移設対象ではない(あるいはlegacy-apiが引き続き所有する)ため、
 * {@link CmsBridgeClient}(CMS操作)・{@link GenerationJobClient}(ジョブ進捗)経由のHTTP呼び出しへ
 * 置き換えた。監査ログ記録に必要なactorId/actorKeycloakSubは、HTTPリクエストにスコープされる情報の
 * ため、コントローラで同期的に取得した値をこのバックグラウンドスレッドの生存期間全体で引き回す。
 * ユーザーのBearerトークンは引き回さない(長いループの途中で失効するため、issue #1249)。
 * publishing-service呼び出しは{@link CmsBridgeClient#deleteMedia}がClient Credentialsトークンで行う。
 */
@Service
public class MediaGarbageCollectionJobRunner {

    private static final Logger log = LoggerFactory.getLogger(MediaGarbageCollectionJobRunner.class);
    private static final long PROGRESS_UPDATE_INTERVAL_MS = 500;

    private final CmsBridgeClient cmsBridgeClient;
    private final GenerationJobClient generationJobClient;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public MediaGarbageCollectionJobRunner(
            CmsBridgeClient cmsBridgeClient,
            GenerationJobClient generationJobClient,
            AuditLogService auditLogService,
            ObjectMapper objectMapper) {
        this.cmsBridgeClient = cmsBridgeClient;
        this.generationJobClient = generationJobClient;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    @Async("mediaGarbageCollectionExecutor")
    public void runDelete(Long jobId, Long projectId, String environment, List<String> mediaIds,
            Long actorId, String actorKeycloakSub) {
        long[] lastReportedAt = {0L};
        List<String> deleted = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        try {
            for (int i = 0; i < mediaIds.size(); i++) {
                String mediaId = mediaIds.get(i);
                try {
                    cmsBridgeClient.deleteMedia(projectId, environment, mediaId);
                    deleted.add(mediaId);
                } catch (RuntimeException e) {
                    log.warn("メディア削除に失敗しました(jobId={}, mediaId={}): {}", jobId, mediaId, e.getMessage());
                    failures.put(mediaId, String.valueOf(e.getMessage()));
                }
                reportProgress(jobId, lastReportedAt, i + 1, mediaIds.size());
            }

            auditLogService.log(actorId, actorKeycloakSub, AuditLogService.ACTION_MEDIA_GARBAGE_COLLECTED, "PROJECT",
                    projectId,
                    toJson(Map.of(
                            "environment", environment,
                            "requestedMediaIds", mediaIds,
                            "deletedMediaIds", deleted,
                            "failures", failures)),
                    null, null);

            String status = deleted.isEmpty() && !failures.isEmpty() ? "failed" : "done";
            generationJobClient.updateStatus(jobId, status, toJson(Map.of(
                    "deletedCount", deleted.size(),
                    "failedCount", failures.size(),
                    "deletedMediaIds", deleted,
                    "failures", failures)));
        } catch (RuntimeException e) {
            log.warn("メディアガベージコレクションジョブが失敗しました(jobId={})", jobId, e);
            generationJobClient.updateStatus(
                    jobId, "failed", toJson(Map.of("error", String.valueOf(e.getMessage()))));
        }
    }

    private void reportProgress(Long jobId, long[] lastReportedAt, int done, int total) {
        long now = System.currentTimeMillis();
        if (now - lastReportedAt[0] < PROGRESS_UPDATE_INTERVAL_MS) {
            return;
        }
        lastReportedAt[0] = now;
        int percent = total > 0 ? (int) Math.round(done * 100.0 / total) : 100;
        generationJobClient.updateStatus(jobId, "running",
                toJson(new JobProgressPayload("deleting", percent, (long) done, (long) total)));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
