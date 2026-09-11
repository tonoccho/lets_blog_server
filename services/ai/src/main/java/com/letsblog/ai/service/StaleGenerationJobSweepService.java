package com.letsblog.ai.service;

import com.letsblog.ai.domain.GenerationJob;
import com.letsblog.ai.repository.GenerationJobRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 長時間{@code running}のまま更新されないgeneration_jobsを検出し{@code failed}として解消する
 * (issue #1083要件4)。
 *
 * <p>media-service側(ModelInstallJobRunner/GenerationJobClient)は、非同期ジョブの完了/失敗を
 * このサービス自身のClient Credentialsトークンで認証して通知するよう修正済み(#1083)であり、
 * かつ通知が下流の一時失敗で届かない場合も再試行する。しかし長時間のネットワーク分断等、
 * それでも最終的に通知が届かないケースは残りうる。generation_jobsの所有権はai-service(#574)に
 * あるため、そのような取り残しを検出・解消するタイムアウト機構はこのサービス自身が持つ
 * (media-serviceは他サービスのテーブルを直接操作できない)。
 *
 * <p>{@code @Scheduled}の既定(初回はアプリ起動直後に実行し、以降は前回の完了から
 * {@code fixedDelay}間隔で実行する)により、起動時の取りこぼし解消と定期的な巡回の両方を兼ねる。
 */
@Component
public class StaleGenerationJobSweepService {

    private static final Logger log = LoggerFactory.getLogger(StaleGenerationJobSweepService.class);
    private static final String RUNNING_STATUS = "running";
    private static final String RESOLVED_STATUS = "failed";

    private final GenerationJobRepository generationJobRepository;
    private final Duration staleTimeout;

    public StaleGenerationJobSweepService(
            GenerationJobRepository generationJobRepository,
            @Value("${app.generation-job.stale-running-timeout-minutes:15}") long staleRunningTimeoutMinutes) {
        this.generationJobRepository = generationJobRepository;
        this.staleTimeout = Duration.ofMinutes(staleRunningTimeoutMinutes);
    }

    @Scheduled(fixedDelayString = "${app.generation-job.stale-sweep-interval-ms:300000}")
    public void sweepStaleRunningJobs() {
        LocalDateTime cutoff = LocalDateTime.now().minus(staleTimeout);
        List<GenerationJob> staleJobs = generationJobRepository.findByStatusAndUpdatedAtBefore(RUNNING_STATUS, cutoff);
        for (GenerationJob job : staleJobs) {
            job.setStatus(RESOLVED_STATUS);
            job.setResultPayload(
                    "{\"error\":\"stale_running_timeout\",\"staleTimeoutMinutes\":" + staleTimeout.toMinutes() + "}");
            generationJobRepository.save(job);
            log.warn(
                    "ジョブ{}が{}分以上{}状態のまま更新されなかったため{}として解消しました(issue #1083)",
                    job.getId(), staleTimeout.toMinutes(), RUNNING_STATUS, RESOLVED_STATUS);
        }
    }
}
