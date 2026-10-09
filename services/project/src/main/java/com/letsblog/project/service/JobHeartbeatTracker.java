package com.letsblog.project.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.client.GenerationJobClient;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 順番待ち・実行中の環境間同期とサイト自動構築のジョブを追跡し、生きている間だけ
 * {@code generation_jobs.updated_at}を進めるハートビートを打つ(issue #1724)。
 * media-serviceの{@code ImageGenerationJobTracker}(#1405)と同じ方式で、Epic #1478の決定による。
 *
 * <p>ai-serviceの{@code StaleGenerationJobSweepService}は{@code running}のまま{@code updated_at}が
 * 既定15分動かないジョブをfailedにする。これらのジョブは(a)実行枠1・待ち行列5で前のジョブを待つ間と、
 * (b)SSH管理サイトからの同期のように1件で最大約18分かかる間、進捗を書かないので、生きていても回収されうる。
 * ジョブ種別ごとにsweepの閾値を延ばすと他種別の回収まで遅れるので、ai-service側は変えず、こちらが
 * 「まだ生きている」ことを示す。project-serviceが落ちればハートビートも止まり、従来どおりsweepが回収する。
 *
 * <p>同じ内容の更新はJPAのdirty checkで{@code updated_at}を動かさないため、毎回{@code heartbeatAt}を変える。
 * ハートビートが終端状態を{@code running}で上書きしないよう、終端の書き込み({@link #complete})と
 * ハートビートは同じ排他の中で行う。
 */
@Component
public class JobHeartbeatTracker {

    private static final Logger log = LoggerFactory.getLogger(JobHeartbeatTracker.class);

    private final GenerationJobClient generationJobClient;
    private final ObjectMapper objectMapper;
    private final Map<Long, String> inFlight = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    public JobHeartbeatTracker(GenerationJobClient generationJobClient, ObjectMapper objectMapper) {
        this.generationJobClient = generationJobClient;
        this.objectMapper = objectMapper;
    }

    /** ジョブを順番待ち({@code queued})として追跡し始める。 */
    public void track(Long jobId) {
        inFlight.put(jobId, "queued");
    }

    /** 追跡中のジョブの直近の段階を更新する(追跡していないジョブは無視する)。 */
    public void updatePhase(Long jobId, String phase) {
        inFlight.computeIfPresent(jobId, (id, old) -> phase);
    }

    public boolean isTracked(Long jobId) {
        return inFlight.containsKey(jobId);
    }

    /** 追跡を外してから終端の書き込みを行う。ハートビートとは排他。 */
    public void complete(Long jobId, Runnable terminalWrite) {
        synchronized (lock) {
            inFlight.remove(jobId);
            terminalWrite.run();
        }
    }

    /** ai-serviceのsweep(既定15分、確認間隔5分)に対して十分短い間隔で打つ。 */
    @Scheduled(fixedDelayString = "${app.job-heartbeat.interval-ms:180000}")
    public void heartbeat() {
        for (Long jobId : inFlight.keySet()) {
            synchronized (lock) {
                String phase = inFlight.get(jobId);
                if (phase == null) {
                    continue;
                }
                try {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("phase", phase);
                    payload.put("heartbeatAt", Instant.now().toString());
                    generationJobClient.updateStatus(jobId, "running", toJson(payload));
                } catch (RuntimeException e) {
                    // 1件の失敗で他のジョブのハートビートを止めない。次の周期で再び打つ。
                    log.warn("Heartbeat for job {} failed", jobId, e);
                }
            }
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
