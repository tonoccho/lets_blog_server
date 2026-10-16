package com.letsblog.project.service;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.letsblog.project.testsupport.FakeHttpServer;
import com.letsblog.project.testsupport.ProjectWorld;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 環境間同期・サイト自動構築ジョブのハートビート(issue #1724)。media-service の
 * ImageGenerationJobTrackerTest と同型。ai-service の滞留回収は {@code running} のまま15分
 * {@code updated_at} が動かないジョブを failed にするので、順番待ちの間も実行中も生存を示す。
 *
 * <p>issue #1817: {@code GenerationJobClient}・{@code ServiceTokenClient}・{@code ObjectMapper}は実物。
 * ai-serviceとトークンエンドポイントの代わりにフェイクHTTPサーバーが応答し、書き込みはその受信記録で確かめる。
 */
@DisplayName("project-service: ジョブのハートビート(issue #1724)")
class JobHeartbeatTrackerTest {

    private static final String PATCH_PATH = "/api/internal/ai/generation-jobs/\\d+";

    private final ProjectWorld world = new ProjectWorld();
    private JobHeartbeatTracker tracker;

    @BeforeEach
    void setUp() {
        world.http.on("PATCH", PATCH_PATH, FakeHttpServer.Response.empty(204));
        tracker = new JobHeartbeatTracker(world.generationJobClient, world.objectMapper);
    }

    @AfterEach
    void close() {
        world.close();
    }

    private List<FakeHttpServer.Request> patches(long jobId) {
        return world.http.requests("PATCH", "/api/internal/ai/generation-jobs/" + jobId);
    }

    private JsonNode payloadOf(FakeHttpServer.Request patch) throws Exception {
        JsonNode body = world.bodyOf(patch);
        assertEquals("running", body.get("status").asText());
        return world.objectMapper.readTree(body.get("resultPayload").asText());
    }

    @Test
    @DisplayName("順番待ちのジョブにも queued の段階でハートビートを書く")
    void queuedBeat() throws Exception {
        tracker.track(7L);

        tracker.heartbeat();

        assertEquals(1, patches(7L).size());
        JsonNode payload = payloadOf(patches(7L).get(0));
        assertEquals("queued", payload.get("phase").asText());
        assertTrue(payload.has("heartbeatAt"));
        assertEquals("Bearer " + ProjectWorld.SERVICE_TOKEN, patches(7L).get(0).header("Authorization"));
    }

    @Test
    @DisplayName("実行中は直近の段階を保ってハートビートを書く")
    void phaseKept() throws Exception {
        tracker.track(7L);
        tracker.updatePhase(7L, "syncing");

        tracker.heartbeat();

        assertEquals("syncing", payloadOf(patches(7L).get(0)).get("phase").asText());
    }

    @Test
    @DisplayName("毎回内容が変わる(JPA の dirty check で updated_at の更新が省かれない)")
    void payloadDiffersEachTime() throws Exception {
        tracker.track(7L);
        tracker.heartbeat();
        Thread.sleep(5);
        tracker.heartbeat();

        List<FakeHttpServer.Request> sent = patches(7L);
        assertEquals(2, sent.size());
        assertNotEquals(world.bodyOf(sent.get(0)).get("resultPayload").asText(),
                world.bodyOf(sent.get(1)).get("resultPayload").asText());
    }

    @Test
    @DisplayName("追跡中のジョブが無ければ何も書かない")
    void nothingTracked() {
        tracker.heartbeat();

        assertTrue(world.http.requests().isEmpty());
    }

    @Test
    @DisplayName("終端の書き込みの後は追跡が外れ、ハートビートが running に戻さない")
    void noRunningAfterComplete() {
        tracker.track(7L);
        AtomicBoolean terminalWritten = new AtomicBoolean();

        tracker.complete(7L, () -> terminalWritten.set(true));
        tracker.heartbeat();

        assertTrue(terminalWritten.get());
        assertFalse(tracker.isTracked(7L));
        assertTrue(world.http.requests("PATCH", PATCH_PATH).isEmpty());
    }

    @Test
    @DisplayName("updatePhase は追跡していないジョブを追加しない")
    void updatePhaseDoesNotTrack() {
        tracker.updatePhase(9L, "syncing");

        assertFalse(tracker.isTracked(9L));
    }

    @Test
    @DisplayName("複数のジョブにそれぞれハートビートを書く")
    void multipleJobs() {
        tracker.track(7L);
        tracker.track(8L);

        tracker.heartbeat();

        assertEquals(1, patches(7L).size());
        assertEquals(1, patches(8L).size());
    }

    @Test
    @DisplayName("1件の書き込みが失敗しても、他のジョブへのハートビートを止めない")
    void oneFailureDoesNotStopOthers() {
        tracker.track(7L);
        tracker.track(8L);
        // サービストークンの取得が1回だけ失敗する(GenerationJobClient はHTTPの失敗を握りつぶすが、
        // トークン取得の失敗は例外として伝わる)。先に書く1件は失敗し、もう1件は書けること。
        world.http.sequence("POST", "/token",
                FakeHttpServer.Response.json(500, "{\"error\":\"down\"}"),
                FakeHttpServer.Response.ok("{\"access_token\":\"svc-token\",\"expires_in\":3600}"));

        tracker.heartbeat();

        assertEquals(2, world.http.requests("POST", "/token").size(), "失敗した後も次のジョブへ進む");
        assertEquals(1, world.http.requests("PATCH", PATCH_PATH).size(), "失敗した1件を除き、もう1件は書けた");
    }

    @Test
    @DisplayName("JSON化に失敗しても空オブジェクトで書く")
    void jsonFailureFallsBack() {
        ObjectMapper broken = new ObjectMapper();
        SimpleModule failing = new SimpleModule();
        failing.addSerializer(String.class, new StdSerializer<>(String.class) {
            @Override
            public void serialize(String value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                throw new IOException("シリアライズ失敗");
            }
        });
        broken.registerModule(failing);
        JobHeartbeatTracker brokenTracker = new JobHeartbeatTracker(world.generationJobClient, broken);
        brokenTracker.track(7L);

        brokenTracker.heartbeat();

        assertEquals("{}", world.bodyOf(patches(7L).get(0)).get("resultPayload").asText());
    }

    @Test
    @DisplayName("周回の途中で終端になったジョブには、ハートビートを書かない")
    void jobCompletedDuringRoundIsSkipped() throws Exception {
        tracker.track(8L);
        tracker.track(9L);
        Thread beat = new Thread(tracker::heartbeat, "heartbeat-under-test");

        // 終端の書き込みはロックの中で行う。その間に別スレッドのハートビートが、列挙済みの 8 のロック待ちに入る。
        // ロックを持つスレッド自身(再入できる)が 8 を終端にすると、ハートビートは引き直した 8 が無いのでスキップする。
        tracker.complete(9L, () -> {
            beat.start();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (beat.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(Thread.State.BLOCKED, beat.getState(), "ハートビートは列挙済みの 8 のロックを待っている");
            tracker.complete(8L, () -> { });
        });
        beat.join(10_000);

        assertFalse(beat.isAlive());
        assertTrue(patches(8L).isEmpty(), "終端になった 8 へはハートビートを書かない");
        assertTrue(patches(9L).isEmpty());
    }
}
