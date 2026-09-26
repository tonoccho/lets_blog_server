package com.letsblog.media.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.media.ai.ImageProvider;
import com.letsblog.media.client.GenerationJobClient;
import com.letsblog.media.client.GenerationJobSummary;
import com.letsblog.media.dto.AiImageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 画像生成を非同期ジョブとして受理する側(issue #1405)。ジョブを作って即座に返し、
 * 生成本体は別クラスのランナーへ渡す(Springの{@code @Async}は自己呼び出しに効かない)。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("media-service: 画像生成ジョブの受理(issue #1405)")
class ImageGenerationJobStarterTest {

    private static final AiImageRequest REQUEST = AiImageRequest.withDefaults("a cat");

    @Mock
    private ImageGenerationService imageGenerationService;
    @Mock
    private ImageGenerationJobRunner runner;
    @Mock
    private GenerationJobClient generationJobClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ImageGenerationJobStarter starter;
    private ImageGenerationJobTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ImageGenerationJobTracker(generationJobClient, objectMapper);
        starter = new ImageGenerationJobStarter(
                imageGenerationService, runner, generationJobClient, objectMapper, tracker);
    }

    @Test
    void ジョブを作ってジョブIDを返しランナーへ渡す() throws Exception {
        GenerationJobSummary created = new GenerationJobSummary(7L, "image_generation", "running", null, null);
        when(imageGenerationService.requireAcceptable(REQUEST)).thenReturn(ImageProvider.COMFYUI);
        when(generationJobClient.create(eq("image_generation"), anyString(), eq("Bearer t"))).thenReturn(created);

        GenerationJobSummary result = starter.start(REQUEST, "Bearer t");

        assertSame(created, result);
        verify(runner).run(7L, REQUEST);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).create(eq("image_generation"), payload.capture(), eq("Bearer t"));
        JsonNode request = objectMapper.readTree(payload.getValue());
        assertEquals("a cat", request.get("prompt").asText());
        assertEquals("COMFYUI", request.get("provider").asText());
    }

    @Test
    void 受け付けられない要求はジョブを作らずそのまま例外にする() {
        when(imageGenerationService.requireAcceptable(REQUEST))
                .thenThrow(new UnsupportedBatchSizeException("上限超過"));

        assertThrows(UnsupportedBatchSizeException.class, () -> starter.start(REQUEST, "Bearer t"));

        verify(generationJobClient, never()).create(anyString(), anyString(), any());
        verify(runner, never()).run(any(), any());
    }

    @Test
    void 実行枠が満杯ならジョブをfailedにして理由を残しジョブを返す() throws Exception {
        GenerationJobSummary created = new GenerationJobSummary(8L, "image_generation", "running", null, null);
        when(imageGenerationService.requireAcceptable(REQUEST)).thenReturn(ImageProvider.CHATGPT);
        when(generationJobClient.create(anyString(), anyString(), any())).thenReturn(created);
        doThrow(new TaskRejectedException("full")).when(runner).run(8L, REQUEST);

        GenerationJobSummary result = starter.start(REQUEST, "Bearer t");

        assertEquals(8L, result.id());
        assertEquals("failed", result.status(), "応答が実際の状態(failed)と食い違ってはならない");
        assertFalse(tracker.isTracked(8L));
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).updateStatus(eq(8L), eq("failed"), payload.capture());
        JsonNode failure = objectMapper.readTree(payload.getValue());
        assertEquals("queue_full", failure.get("errorType").asText());
    }

    @Test
    void 指定した枚数と回数をリクエストのペイロードに残す() throws Exception {
        when(imageGenerationService.requireAcceptable(any())).thenReturn(ImageProvider.CHATGPT);
        when(generationJobClient.create(anyString(), anyString(), any()))
                .thenReturn(new GenerationJobSummary(9L, "image_generation", "running", null, null));
        AiImageRequest request = new AiImageRequest(
                "a cat", null, null, null, null, null, null, null, null, 4, 3, null, null, null, null);

        starter.start(request, "Bearer t");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).create(eq("image_generation"), payload.capture(), any());
        JsonNode json = objectMapper.readTree(payload.getValue());
        assertEquals(4, json.get("batchSize").asInt());
        assertEquals(3, json.get("batchCount").asInt());
    }

    @Test
    void JSON化に失敗しても空オブジェクトで受理を続ける() throws Exception {
        ObjectMapper broken = org.mockito.Mockito.mock(ObjectMapper.class);
        when(broken.writeValueAsString(any())).thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("x") { });
        ImageGenerationJobStarter brokenStarter =
                new ImageGenerationJobStarter(
                        imageGenerationService, runner, generationJobClient, broken, tracker);
        when(imageGenerationService.requireAcceptable(REQUEST)).thenReturn(ImageProvider.COMFYUI);
        when(generationJobClient.create(anyString(), anyString(), any()))
                .thenReturn(new GenerationJobSummary(9L, "image_generation", "running", null, null));

        brokenStarter.start(REQUEST, "Bearer t");

        verify(generationJobClient).create(eq("image_generation"), eq("{}"), any());
    }

    @Test
    void 受理したジョブはランナーへ渡す前にハートビートの追跡へ入る() {
        when(imageGenerationService.requireAcceptable(REQUEST)).thenReturn(ImageProvider.COMFYUI);
        when(generationJobClient.create(anyString(), anyString(), any()))
                .thenReturn(new GenerationJobSummary(9L, "image_generation", "running", null, null));

        starter.start(REQUEST, "Bearer t");

        assertTrue(tracker.isTracked(9L));
    }
}
