package com.letsblog.media.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.media.client.GenerationJobClient;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 実行中・待機中の画像生成ジョブのハートビート(issue #1405 レビュー指摘)。
 *
 * <p>ai-serviceの{@code StaleGenerationJobSweepService}は{@code running}のまま15分間
 * {@code updated_at}が動かないジョブをfailedにする。画像生成ジョブは(a)専用Executorの待ち行列で
 * 待つ間と(b)1リピートのポーリング中(最大約3300秒)に進捗を書かないため、生きていても回収される。
 * media-serviceが生きている間だけ、追跡中のジョブの{@code updated_at}を定期的に進める。
 * 内容が同じ更新はJPAのdirty checkで{@code updated_at}を動かさないので、毎回{@code heartbeatAt}を変える。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("media-service: 画像生成ジョブのハートビート(issue #1405)")
class ImageGenerationJobTrackerTest {

    @Mock
    private GenerationJobClient generationJobClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ImageGenerationJobTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ImageGenerationJobTracker(generationJobClient, objectMapper);
    }

    private JsonNode lastPayload(int times) throws Exception {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient, times(times)).updateStatus(eq(7L), eq("running"), payload.capture());
        return objectMapper.readTree(payload.getValue());
    }

    @Test
    void 待機中のジョブにもqueuedの進捗でハートビートを書く() throws Exception {
        tracker.track(7L);

        tracker.heartbeat();

        JsonNode payload = lastPayload(1);
        assertEquals("queued", payload.get("phase").asText());
        assertEquals(0, payload.get("percent").asInt());
        assertTrue(payload.has("heartbeatAt"));
    }

    @Test
    void 実行中は直近の進捗を保ったままハートビートを書く() throws Exception {
        tracker.track(7L);
        tracker.update(7L, "generating", 50);

        tracker.heartbeat();

        JsonNode payload = lastPayload(1);
        assertEquals("generating", payload.get("phase").asText());
        assertEquals(50, payload.get("percent").asInt());
    }

    @Test
    void 毎回内容が変わる() throws Exception {
        tracker.track(7L);
        tracker.heartbeat();
        Thread.sleep(5);
        tracker.heartbeat();

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient, times(2)).updateStatus(eq(7L), eq("running"), payload.capture());
        assertNotEquals(payload.getAllValues().get(0), payload.getAllValues().get(1));
    }

    @Test
    void 追跡中のジョブが無ければ何も書かない() {
        tracker.heartbeat();

        verifyNoInteractions(generationJobClient);
    }

    @Test
    void 終端通知の後はハートビートを書かずdoneを上書きしない() {
        tracker.track(7L);
        AtomicBoolean terminalWritten = new AtomicBoolean();

        tracker.complete(7L, () -> terminalWritten.set(true));
        tracker.heartbeat();

        assertTrue(terminalWritten.get());
        assertFalse(tracker.isTracked(7L));
        verify(generationJobClient, never()).updateStatus(anyLong(), anyString(), anyString());
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

        verify(generationJobClient).updateStatus(eq(7L), eq("running"), anyString());
        verify(generationJobClient).updateStatus(eq(8L), eq("running"), anyString());
    }

    @Test
    void JSON化に失敗しても空オブジェクトで書く() throws Exception {
        ObjectMapper broken = org.mockito.Mockito.mock(ObjectMapper.class);
        org.mockito.Mockito.when(broken.writeValueAsString(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("x") { });
        ImageGenerationJobTracker brokenTracker = new ImageGenerationJobTracker(generationJobClient, broken);
        brokenTracker.track(7L);

        brokenTracker.heartbeat();

        verify(generationJobClient).updateStatus(7L, "running", "{}");
    }
}
