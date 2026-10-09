package com.letsblog.project.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.client.GenerationJobClient;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 環境間同期・サイト自動構築ジョブのハートビート(issue #1724)。media-service の
 * ImageGenerationJobTrackerTest と同型。ai-service の滞留回収は {@code running} のまま15分
 * {@code updated_at} が動かないジョブを failed にするので、順番待ちの間も実行中も生存を示す。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("project-service: ジョブのハートビート(issue #1724)")
class JobHeartbeatTrackerTest {

    @Mock
    private GenerationJobClient generationJobClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JobHeartbeatTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new JobHeartbeatTracker(generationJobClient, objectMapper);
    }

    private JsonNode payloadOf(int times) throws Exception {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient, times(times)).updateStatus(eq(7L), eq("running"), payload.capture());
        return objectMapper.readTree(payload.getValue());
    }

    @Test
    @DisplayName("順番待ちのジョブにも queued の段階でハートビートを書く")
    void queuedBeat() throws Exception {
        tracker.track(7L);

        tracker.heartbeat();

        JsonNode payload = payloadOf(1);
        assertEquals("queued", payload.get("phase").asText());
        assertTrue(payload.has("heartbeatAt"));
    }

    @Test
    @DisplayName("実行中は直近の段階を保ってハートビートを書く")
    void phaseKept() throws Exception {
        tracker.track(7L);
        tracker.updatePhase(7L, "syncing");

        tracker.heartbeat();

        assertEquals("syncing", payloadOf(1).get("phase").asText());
    }

    @Test
    @DisplayName("毎回内容が変わる(JPA の dirty check で updated_at の更新が省かれない)")
    void payloadDiffersEachTime() throws Exception {
        tracker.track(7L);
        tracker.heartbeat();
        Thread.sleep(5);
        tracker.heartbeat();

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient, times(2)).updateStatus(eq(7L), eq("running"), payload.capture());
        assertNotEquals(payload.getAllValues().get(0), payload.getAllValues().get(1));
    }

    @Test
    @DisplayName("追跡中のジョブが無ければ何も書かない")
    void nothingTracked() {
        tracker.heartbeat();

        verifyNoInteractions(generationJobClient);
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
        verify(generationJobClient, never()).updateStatus(anyLong(), anyString(), anyString());
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

        verify(generationJobClient).updateStatus(eq(7L), eq("running"), anyString());
        verify(generationJobClient).updateStatus(eq(8L), eq("running"), anyString());
    }

    @Test
    @DisplayName("1件の書き込みが失敗しても、他のジョブへのハートビートを止めない")
    void oneFailureDoesNotStopOthers() {
        tracker.track(7L);
        tracker.track(8L);
        doThrow(new RuntimeException("down")).when(generationJobClient).updateStatus(eq(7L), eq("running"), anyString());

        tracker.heartbeat();

        verify(generationJobClient).updateStatus(eq(7L), eq("running"), anyString());
        verify(generationJobClient).updateStatus(eq(8L), eq("running"), anyString());
    }

    @Test
    @DisplayName("JSON化に失敗しても空オブジェクトで書く")
    void jsonFailureFallsBack() throws Exception {
        ObjectMapper broken = mock(ObjectMapper.class);
        when(broken.writeValueAsString(any()))
                .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("x") { });
        JobHeartbeatTracker brokenTracker = new JobHeartbeatTracker(generationJobClient, broken);
        brokenTracker.track(7L);

        brokenTracker.heartbeat();

        verify(generationJobClient).updateStatus(7L, "running", "{}");
    }

    @Test
    @DisplayName("周回の途中で終端になったジョブには、ハートビートを書かない")
    void jobCompletedDuringRoundIsSkipped() {
        tracker.track(7L);
        tracker.track(8L);
        // 7 への書き込み中に 8 が終端になった状況(周回は列挙済みの 8 を引き直して null を見る)。
        org.mockito.Mockito.doAnswer(inv -> {
            tracker.complete(8L, () -> { });
            return null;
        }).when(generationJobClient).updateStatus(eq(7L), eq("running"), anyString());

        tracker.heartbeat();

        verify(generationJobClient, never()).updateStatus(eq(8L), anyString(), anyString());
    }
}
