package com.letsblog.media.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.media.testsupport.BrokenObjectMapper;
import com.letsblog.media.testsupport.MediaWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 画像生成ジョブのハートビート(issue #1405)。
 *
 * <p>待ち行列で待っているジョブと実行中のジョブの両方に、一定間隔で{@code running}の更新を書く。
 * ai-service側のstale回収は更新時刻で判定するため、更新が止まったジョブは回収される。終端通知(done/failed)の後に
 * ハートビートが{@code running}を上書きしないことが、このクラスの主な責務。
 *
 * <p>issue #1819: {@code GenerationJobClient}・{@code ObjectMapper}は実物で、ai-serviceをフェイクHTTPサーバーが演じる。
 */
@DisplayName("media-service: 画像生成ジョブのハートビート(issue #1405)")
class ImageGenerationJobTrackerTest {

    private final MediaWorld world = new MediaWorld();
    private final ImageGenerationJobTracker tracker = world.tracker;

    @AfterEach
    void close() {
        world.close();
    }

    private List<MediaWorld.JobUpdate> heartbeats() {
        return world.jobUpdates("running");
    }

    @Test
    void 待機中のジョブにもqueuedの進捗でハートビートを書く() {
        tracker.track(7L);

        tracker.heartbeat();

        assertEquals(1, heartbeats().size());
        assertEquals(7L, heartbeats().get(0).jobId());
        JsonNode payload = heartbeats().get(0).payload();
        assertEquals("queued", payload.get("phase").asText());
        assertEquals(0, payload.get("percent").asInt());
        assertTrue(payload.has("heartbeatAt"));
    }

    @Test
    void 実行中は直近の進捗を保ったままハートビートを書く() {
        tracker.track(7L);
        tracker.update(7L, "generating", 50);

        tracker.heartbeat();

        JsonNode payload = heartbeats().get(0).payload();
        assertEquals("generating", payload.get("phase").asText());
        assertEquals(50, payload.get("percent").asInt());
    }

    @Test
    void 毎回内容が変わる() throws Exception {
        tracker.track(7L);
        tracker.heartbeat();
        Thread.sleep(5);
        tracker.heartbeat();

        assertEquals(2, heartbeats().size());
        assertNotEquals(heartbeats().get(0).payload(), heartbeats().get(1).payload());
    }

    @Test
    void 追跡中のジョブが無ければ何も書かない() {
        tracker.heartbeat();

        assertTrue(world.jobUpdates().isEmpty());
    }

    @Test
    void 終端通知の後はハートビートを書かずdoneを上書きしない() {
        tracker.track(7L);
        AtomicBoolean terminalWritten = new AtomicBoolean();

        tracker.complete(7L, () -> terminalWritten.set(true));
        tracker.heartbeat();

        assertTrue(terminalWritten.get());
        assertFalse(tracker.isTracked(7L));
        assertTrue(world.jobUpdates().isEmpty());
    }

    @Test
    void updateは追跡していないジョブを追加しない() {
        tracker.update(9L, "generating", 10);

        assertFalse(tracker.isTracked(9L));
    }

    @Test
    void 複数のジョブにそれぞれハートビートを書く() {
        tracker.track(7L);
        tracker.track(8L);

        tracker.heartbeat();

        assertEquals(List.of(7L, 8L), heartbeats().stream().map(MediaWorld.JobUpdate::jobId).sorted().toList());
    }

    @Test
    void JSON化に失敗しても空オブジェクトで書く() {
        ImageGenerationJobTracker brokenTracker =
                new ImageGenerationJobTracker(world.generationJobClient, BrokenObjectMapper.create());
        brokenTracker.track(7L);

        brokenTracker.heartbeat();

        var request = world.http.requests("PATCH", "/api/internal/ai/generation-jobs/7").get(0);
        assertEquals("running", world.bodyOf(request).get("status").asText());
        assertEquals("{}", world.bodyOf(request).get("resultPayload").asText());
    }

    /**
     * 周回の途中で終端になったジョブにはハートビートを書かない。ロックを持つ側が終端の書き込みの中で
     * 別スレッドの{@code heartbeat()}が待ち状態(BLOCKED)になるのを確かめてから、再入できるロックでそのジョブを終端にする。
     * 待ち合わせはスレッドの状態で行うので、タイミングに依らず決定的。
     */
    @Test
    void 周回の途中で終端になったジョブにはハートビートを書かない() throws Exception {
        tracker.track(7L);
        tracker.track(8L);
        Thread[] heartbeatThread = new Thread[1];

        tracker.complete(8L, () -> {
            heartbeatThread[0] = new Thread(tracker::heartbeat, "heartbeat");
            heartbeatThread[0].start();
            long deadline = System.currentTimeMillis() + 10_000;
            while (heartbeatThread[0].getState() != Thread.State.BLOCKED) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException("heartbeat thread did not block on the lock");
                }
                Thread.onSpinWait();
            }
            // 7は周回のキーに入っているが、ロックを待つ間に終端になる。
            tracker.complete(7L, () -> { });
        });
        heartbeatThread[0].join(10_000);

        assertFalse(heartbeatThread[0].isAlive());
        assertTrue(world.jobUpdates().isEmpty(), "終端になったジョブにはrunningを書かない");
    }
}
