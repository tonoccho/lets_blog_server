package com.letsblog.publishing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.client.GenerationJobBridgeException;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.common.client.GenerationJobSummary;
import com.letsblog.publishing.client.MediaGeneratedImageClient;
import com.letsblog.publishing.client.MediaSettingsBridgeClient;
import com.letsblog.publishing.render.MediaRenderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** アセット画像の全環境アップロードジョブの受理側(issue #1764)。{@code BulkInstallJobStarterTest}と同型。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("publishing-service: アセット画像アップロードジョブの受理(issue #1764)")
class AssetImageUploadJobStarterTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 1, 2, 3);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 1, 2};

    @Mock
    private GenerationJobClient generationJobClient;
    @Mock
    private AssetImageUploadJobRunner runner;
    @Mock
    private MediaGeneratedImageClient mediaGeneratedImageClient;
    @Mock
    private MediaSettingsBridgeClient mediaSettingsBridgeClient;
    @Mock
    private CurrentActorService currentActorService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AssetImageUploadJobStarter starter;

    @BeforeEach
    void setUp() {
        starter = new AssetImageUploadJobStarter(generationJobClient, runner, mediaGeneratedImageClient,
                mediaSettingsBridgeClient, currentActorService, objectMapper);
    }

    @Test
    @DisplayName("ジョブ種別は安定した非AIの名前")
    void jobTypeIsStable() {
        assertEquals("asset_image_upload", AssetImageUploadJobStarter.JOB_TYPE);
    }

    @Test
    @DisplayName("リクエストスレッドで画像・長辺px・操作者を解決し、利用者のBearerでジョブを作り、取得済みのものをランナーへメモリで渡す")
    void startsJob() throws Exception {
        when(mediaGeneratedImageClient.fetchImageFile(5L)).thenReturn(PNG);
        when(mediaSettingsBridgeClient.resolveArticleImageLongEdgePx(3L)).thenReturn(1300);
        when(currentActorService.getCurrentActorId()).thenReturn(7L);
        when(currentActorService.getAuthorizationHeader()).thenReturn("Bearer abc");
        GenerationJobSummary job = new GenerationJobSummary(31L, "asset_image_upload", "running", NOW, NOW);
        when(generationJobClient.create(eq("asset_image_upload"), any(), eq("Bearer abc"))).thenReturn(job);

        GenerationJobSummary result = starter.start(3L, 5L);

        assertSame(job, result);
        verify(runner).run(31L, 3L, PNG, "comfyui-5.png", "image/png", 1300, 7L);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).create(eq("asset_image_upload"), payload.capture(), eq("Bearer abc"));
        JsonNode json = objectMapper.readTree(payload.getValue());
        // キューUIが結果の遷移先を決めるのに projectId と generatedImageId を読む。画像本体は載せない。
        assertEquals(3L, json.get("projectId").asLong());
        assertEquals(5L, json.get("generatedImageId").asLong());
        assertEquals(2, json.size());
    }

    @Test
    @DisplayName("JPEGの内容ならimage/jpegと拡張子jpgでランナーへ渡す")
    void jpegIsDetected() {
        when(mediaGeneratedImageClient.fetchImageFile(6L)).thenReturn(JPEG);
        when(mediaSettingsBridgeClient.resolveArticleImageLongEdgePx(3L)).thenReturn(1300);
        when(generationJobClient.create(any(), any(), any()))
                .thenReturn(new GenerationJobSummary(32L, "asset_image_upload", "running", NOW, NOW));

        starter.start(3L, 6L);

        verify(runner).run(eq(32L), eq(3L), eq(JPEG), eq("comfyui-6.jpg"), eq("image/jpeg"), eq(1300), any());
    }

    @Test
    @DisplayName("画像が取れなければ、ジョブを作らずランナーも起動せず例外を伝える")
    void imageFetchFailureCreatesNoJob() {
        when(mediaGeneratedImageClient.fetchImageFile(99L))
                .thenThrow(new MediaRenderException("media-serviceの生成画像取得呼び出しに失敗しました", null));

        assertThrows(MediaRenderException.class, () -> starter.start(3L, 99L));

        verify(generationJobClient, never()).create(any(), any(), any());
        verify(runner, never()).run(any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("実行枠と待ち行列が満杯なら、ジョブを queue_full の failed にして返す")
    void queueFull() throws Exception {
        when(mediaGeneratedImageClient.fetchImageFile(5L)).thenReturn(PNG);
        when(mediaSettingsBridgeClient.resolveArticleImageLongEdgePx(3L)).thenReturn(1300);
        when(generationJobClient.create(any(), any(), any()))
                .thenReturn(new GenerationJobSummary(33L, "asset_image_upload", "running", NOW, NOW));
        doThrow(new TaskRejectedException("full")).when(runner)
                .run(any(), any(), any(), any(), any(), anyInt(), any());

        GenerationJobSummary result = starter.start(3L, 5L);

        assertEquals("failed", result.status());
        assertEquals(33L, result.id());
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).updateStatus(eq(33L), eq("failed"), payload.capture());
        JsonNode json = objectMapper.readTree(payload.getValue());
        assertEquals("queue_full", json.get("errorType").asText());
        assertFalse(json.get("error").asText().isBlank());
    }

    @Test
    @DisplayName("ジョブを作れなければ(ai-service 不達)ランナーを起動せず例外を伝える")
    void createFailurePropagates() {
        when(mediaGeneratedImageClient.fetchImageFile(5L)).thenReturn(PNG);
        when(mediaSettingsBridgeClient.resolveArticleImageLongEdgePx(3L)).thenReturn(1300);
        when(generationJobClient.create(any(), any(), any())).thenThrow(new GenerationJobBridgeException("down", null));

        assertThrows(GenerationJobBridgeException.class, () -> starter.start(3L, 5L));

        verify(runner, never()).run(any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("ジョブの要求内容のJSON化に失敗しても、空のJSONで受理を続ける")
    void payloadSerializationFailureFallsBackToEmptyJson() throws Exception {
        ObjectMapper broken = org.mockito.Mockito.mock(ObjectMapper.class);
        when(broken.writeValueAsString(any())).thenThrow(new RuntimeException("boom"));
        AssetImageUploadJobStarter brokenStarter = new AssetImageUploadJobStarter(generationJobClient, runner,
                mediaGeneratedImageClient, mediaSettingsBridgeClient, currentActorService, broken);
        when(mediaGeneratedImageClient.fetchImageFile(5L)).thenReturn(PNG);
        when(mediaSettingsBridgeClient.resolveArticleImageLongEdgePx(3L)).thenReturn(1300);
        when(generationJobClient.create(any(), any(), any()))
                .thenReturn(new GenerationJobSummary(34L, "asset_image_upload", "running", NOW, NOW));

        brokenStarter.start(3L, 5L);

        verify(generationJobClient).create(eq("asset_image_upload"), eq("{}"), any());
    }
}
