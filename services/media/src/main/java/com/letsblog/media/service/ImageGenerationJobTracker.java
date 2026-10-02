package com.letsblog.media.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.client.GenerationJobClient;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 待機中・実行中の画像生成ジョブを追跡し、生きている間だけ{@code generation_jobs.updated_at}を
 * 進めるハートビートを打つ(issue #1405 レビュー指摘)。
 *
 * <p>ai-serviceの{@code StaleGenerationJobSweepService}は{@code running}のまま
 * {@code updated_at}が既定15分動かないジョブをfailedにする。画像生成ジョブは、専用Executor
 * (並列度1)の待ち行列で待つ間と、1リピートのポーリング中(最大約3300秒)に進捗を書かないため、
 * 生きていても回収されうる。ジョブ種別ごとにsweepのタイムアウトを延ばすと、他の種別
 * (チェックポイントのダウンロード等)の取り残し回収まで遅らせてしまうので、ai-service側は
 * 変えず、こちらがハートビートで「まだ生きている」ことを示す。media-serviceが落ちればハートビートも
 * 止まり、従来どおりsweepが15分後に回収する。
 *
 * <p>同じ内容の更新はJPAのdirty checkで{@code updated_at}を動かさないため、毎回
 * {@code heartbeatAt}を変えて書く。ハートビートが終端状態を{@code running}で上書きしないよう、
 * 終端通知({@link #complete})とハートビートは同じ排他の中で行う。
 */
@Component
public class ImageGenerationJobTracker {

    private record Progress(String phase, int percent) {
    }

    private final GenerationJobClient generationJobClient;
    private final ObjectMapper objectMapper;
    private final Map<Long, Progress> inFlight = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    public ImageGenerationJobTracker(GenerationJobClient generationJobClient, ObjectMapper objectMapper) {
        this.generationJobClient = generationJobClient;
        this.objectMapper = objectMapper;
    }

    /** ジョブを待機中として追跡し始める。 */
    public void track(Long jobId) {
        inFlight.put(jobId, new Progress("queued", 0));
    }

    /** 追跡中のジョブの直近の進捗を更新する(追跡していないジョブは無視する)。 */
    public void update(Long jobId, String phase, int percent) {
        inFlight.computeIfPresent(jobId, (id, old) -> new Progress(phase, percent));
    }

    public boolean isTracked(Long jobId) {
        return inFlight.containsKey(jobId);
    }

    /** 追跡を外してから終端通知を書く。ハートビートとは排他。 */
    public void complete(Long jobId, Runnable terminalWrite) {
        synchronized (lock) {
            inFlight.remove(jobId);
            terminalWrite.run();
        }
    }

    /** ai-serviceのsweep(既定15分、確認間隔5分)に対して十分短い間隔で打つ。 */
    @Scheduled(fixedDelayString = "${app.image-generation-job.heartbeat-interval-ms:180000}")
    public void heartbeat() {
        for (Long jobId : inFlight.keySet()) {
            synchronized (lock) {
                Progress progress = inFlight.get(jobId);
                if (progress == null) {
                    continue;
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("phase", progress.phase());
                payload.put("percent", progress.percent());
                payload.put("bytesDone", null);
                payload.put("bytesTotal", null);
                payload.put("heartbeatAt", Instant.now().toString());
                generationJobClient.updateStatus(jobId, "running", toJson(payload));
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
