package com.letsblog.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.domain.GenerationJob;
import com.letsblog.api.domain.Site;
import com.letsblog.api.repository.GenerationJobRepository;
import com.letsblog.api.repository.SiteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * メディアガベージコレクションの一括削除をバックグラウンドスレッドで実行する(issue #500)。
 * Spring の {@code @Async} は同一クラス内の自己呼び出しには効かない(プロキシを経由しないため)ため、
 * ジョブを起動する{@link MediaGarbageCollectionService}とは別クラスに分離している
 * (media-serviceへ移設済みの{@code ModelInstallJobRunner}と同じ構成、issue #573 stage2)。
 * <p>
 * 監査ログ記録に必要な{@link CurrentActorService}はHTTPリクエストにスコープされるため、
 * リクエストの終わったこの非同期スレッドからは使えない。そのため宣言的な{@code @AuditLog}
 * アノテーションは使わず、コントローラで同期的に取得したactorIdを引き回して
 * {@link AuditLogService#log}を直接呼ぶ({@code ProjectController.deletePostEverywhere}と同じ回避策)。
 */
@Service
public class MediaGarbageCollectionJobRunner {

    private static final Logger log = LoggerFactory.getLogger(MediaGarbageCollectionJobRunner.class);
    private static final long PROGRESS_UPDATE_INTERVAL_MS = 500;

    private final SiteRepository siteRepository;
    private final SiteService siteService;
    private final CmsAdapterFactory cmsAdapterFactory;
    private final GenerationJobRepository generationJobRepository;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public MediaGarbageCollectionJobRunner(
            SiteRepository siteRepository,
            SiteService siteService,
            CmsAdapterFactory cmsAdapterFactory,
            GenerationJobRepository generationJobRepository,
            AuditLogService auditLogService,
            ObjectMapper objectMapper) {
        this.siteRepository = siteRepository;
        this.siteService = siteService;
        this.cmsAdapterFactory = cmsAdapterFactory;
        this.generationJobRepository = generationJobRepository;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    @Async("mediaGarbageCollectionExecutor")
    public void runDelete(Long jobId, Long siteId, String environment, Long projectId, List<String> mediaIds,
            Long actorId, String actorKeycloakSub) {
        long[] lastReportedAt = {0L};
        List<String> deleted = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        try {
            Site site = siteRepository.findById(siteId)
                    .orElseThrow(() -> new IllegalArgumentException("id " + siteId + " のサイトは見つかりません"));
            CmsCredentials credentials = siteService.getCredentials(site.getSiteKey());
            CmsAdapter adapter = cmsAdapterFactory.resolve(credentials.cmsType());

            for (int i = 0; i < mediaIds.size(); i++) {
                String mediaId = mediaIds.get(i);
                try {
                    adapter.deleteMedia(credentials, mediaId);
                    deleted.add(mediaId);
                } catch (RuntimeException e) {
                    log.warn("メディア削除に失敗しました(jobId={}, mediaId={}): {}", jobId, mediaId, e.getMessage());
                    failures.put(mediaId, String.valueOf(e.getMessage()));
                }
                reportProgress(jobId, lastReportedAt, i + 1, mediaIds.size());
            }

            auditLogService.log(actorId, actorKeycloakSub, AuditLogAction.MEDIA_GARBAGE_COLLECTED, "PROJECT", projectId,
                    toJson(Map.of(
                            "environment", environment,
                            "requestedMediaIds", mediaIds,
                            "deletedMediaIds", deleted,
                            "failures", failures)),
                    null, null);

            GenerationJob job = generationJobRepository.findById(jobId).orElseThrow();
            job.setStatus(deleted.isEmpty() && !failures.isEmpty() ? "failed" : "done");
            job.setResultPayload(toJson(Map.of(
                    "deletedCount", deleted.size(),
                    "failedCount", failures.size(),
                    "deletedMediaIds", deleted,
                    "failures", failures)));
            generationJobRepository.save(job);
        } catch (RuntimeException e) {
            log.warn("メディアガベージコレクションジョブが失敗しました(jobId={})", jobId, e);
            generationJobRepository.findById(jobId).ifPresent(job -> {
                job.setStatus("failed");
                job.setResultPayload(toJson(Map.of("error", String.valueOf(e.getMessage()))));
                generationJobRepository.save(job);
            });
        }
    }

    private void reportProgress(Long jobId, long[] lastReportedAt, int done, int total) {
        long now = System.currentTimeMillis();
        if (now - lastReportedAt[0] < PROGRESS_UPDATE_INTERVAL_MS) {
            return;
        }
        lastReportedAt[0] = now;
        int percent = total > 0 ? (int) Math.round(done * 100.0 / total) : 100;
        generationJobRepository.findById(jobId).ifPresent(job -> {
            job.setResultPayload(toJson(new JobProgressPayload("deleting", percent, (long) done, (long) total)));
            generationJobRepository.save(job);
        });
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
