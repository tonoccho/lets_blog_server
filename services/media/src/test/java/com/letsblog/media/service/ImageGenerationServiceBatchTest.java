package com.letsblog.media.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.media.ai.ChatGptImageClient;
import com.letsblog.media.ai.ComfyUiClient;
import com.letsblog.media.ai.ComfyUiImage;
import com.letsblog.media.ai.ImageProvider;
import com.letsblog.media.ai.SeedResolver;
import com.letsblog.media.client.AiGenerationClient;
import com.letsblog.media.client.GenerationJobClient;
import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.dto.AiImageRequest;
import com.letsblog.media.service.ImageGenerationService.BatchOutcome;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 非同期ジョブから使う「ジョブ管理を含まない」生成本体(issue #1405)。
 * 同期経路{@code generateImage}が自前でジョブを作るのに対し、こちらは既に作られたジョブの
 * 中で動くため、ジョブを作らず、Base64を保持せず、リピート完了を通知する。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("media-service: 生成本体generateBatch(issue #1405)")
class ImageGenerationServiceBatchTest {

    @Mock
    private AiGenerationClient aiGenerationClient;
    @Mock
    private ComfyUiClient comfyUiClient;
    @Mock
    private ChatGptImageClient chatGptImageClient;
    @Mock
    private ImageModelService imageModelService;
    @Mock
    private ComfyUiModelService comfyUiModelService;
    @Mock
    private GeneratedImageCreationService generatedImageCreationService;
    @Mock
    private GenerationJobClient generationJobClient;
    @Mock
    private ProjectImageDefaultsResolver defaultsResolver;
    @Mock
    private ProhibitedContentFilterService prohibitedContentFilterService;
    @Mock
    private HttpServletRequest request;

    private ImageGenerationService service;

    @BeforeEach
    void setUp() {
        service = new ImageGenerationService(
                aiGenerationClient, comfyUiClient, chatGptImageClient, imageModelService, comfyUiModelService,
                generatedImageCreationService, generationJobClient, new ObjectMapper(), defaultsResolver,
                prohibitedContentFilterService, new SafetyNegativePromptService("a", "b", "c"),
                new SeedResolver(), request);
        when(imageModelService.getSelectedProvider(any())).thenReturn(ImageProvider.COMFYUI);
        when(comfyUiModelService.getSelectedCheckpointOrGlobalDefault(any())).thenReturn("g.safetensors");
        when(aiGenerationClient.generate(any(), anyString(), any())).thenReturn("{\"tags\":[]}");
        AtomicLong ids = new AtomicLong(100);
        when(generatedImageCreationService.create(any())).thenAnswer(inv -> {
            GeneratedImage saved = new GeneratedImage();
            saved.setId(ids.incrementAndGet());
            return saved;
        });
    }

    private static List<ComfyUiImage> images(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new ComfyUiImage("i" + i + ".png", new byte[] {1}, "image/png"))
                .toList();
    }

    private static AiImageRequest repeated(int batchSize, int batchCount) {
        return new AiImageRequest(
                "a cat", null, null, null, null, null, 1L, null, null, batchSize, batchCount, null, null, null, null);
    }

    @Test
    void ジョブを作らず保存した画像のIDを返す() {
        when(comfyUiClient.generateImage(any())).thenAnswer(inv -> images(2));

        BatchOutcome outcome = service.generateBatch(repeated(2, 1), false, (done, total) -> { });

        assertEquals(List.of(101L, 102L), outcome.images().stream().map(i -> i.id()).toList());
        assertEquals(1, outcome.succeededRepeats());
        assertEquals(0, outcome.failedRepeats());
        assertEquals(false, outcome.aborted());
        verify(generationJobClient, never()).create(anyString(), anyString(), any());
        verify(generationJobClient, never()).updateStatus(any(), anyString(), anyString());
    }

    @Test
    void includeImageDataがfalseならBase64を保持しない() {
        when(comfyUiClient.generateImage(any())).thenAnswer(inv -> images(1));

        BatchOutcome outcome = service.generateBatch(repeated(1, 1), false, (done, total) -> { });

        assertNull(outcome.images().get(0).dataBase64());
    }

    @Test
    void includeImageDataがtrueならBase64を返す() {
        when(comfyUiClient.generateImage(any())).thenAnswer(inv -> images(1));

        BatchOutcome outcome = service.generateBatch(repeated(1, 1), true, (done, total) -> { });

        assertNotNull(outcome.images().get(0).dataBase64());
    }

    @Test
    void リピートが終わるたびに完了数と総数を通知する() {
        when(comfyUiClient.generateImage(any())).thenAnswer(inv -> images(1));
        List<String> seen = new ArrayList<>();

        service.generateBatch(repeated(1, 3), false, (done, total) -> seen.add(done + "/" + total));

        assertEquals(List.of("1/3", "2/3", "3/3"), seen);
    }

    @Test
    void 途中で失敗しても成功分を返し失敗も通知に含める() {
        when(comfyUiClient.generateImage(any()))
                .thenAnswer(inv -> images(1))
                .thenThrow(new IllegalStateException("x"))
                .thenAnswer(inv -> images(1));
        List<String> seen = new ArrayList<>();

        BatchOutcome outcome = service.generateBatch(repeated(1, 3), false, (done, total) -> seen.add(done + "/" + total));

        assertEquals(2, outcome.succeededRepeats());
        assertEquals(1, outcome.failedRepeats());
        assertEquals(3, outcome.attemptedRepeats());
        assertEquals(3, seen.size());
    }

    @Test
    void 全リピートが失敗したら最初の失敗を投げる() {
        when(comfyUiClient.generateImage(any())).thenThrow(new IllegalStateException("first"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.generateBatch(repeated(1, 2), false, (done, total) -> { }));

        assertEquals("first", e.getMessage());
    }

    @Test
    void 禁止コンテンツは生成前にそのまま投げる() {
        org.mockito.Mockito.doThrow(new ProhibitedContentException("禁止"))
                .when(prohibitedContentFilterService).check(anyString(), org.mockito.ArgumentMatchers.anyBoolean(),
                        org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean());

        assertThrows(ProhibitedContentException.class,
                () -> service.generateBatch(repeated(1, 1), false, (done, total) -> { }));

        verify(comfyUiClient, never()).generateImage(any());
    }

    @Test
    void requireAcceptableは受理できるプロバイダを返す() {
        assertEquals(ImageProvider.COMFYUI, service.requireAcceptable(repeated(2, 1)));
    }

    @Test
    void requireAcceptableはプロバイダの上限を超える枚数を拒否する() {
        when(imageModelService.getSelectedProvider(any())).thenReturn(ImageProvider.CHATGPT);

        UnsupportedBatchSizeException e = assertThrows(UnsupportedBatchSizeException.class,
                () -> service.requireAcceptable(repeated(11, 1)));

        assertTrue(e.getMessage().contains("CHATGPT"));
    }

    @Test
    void requireAcceptableは枚数未指定でも1枚として扱い受理する() {
        assertEquals(ImageProvider.COMFYUI, service.requireAcceptable(AiImageRequest.withDefaults("a cat")));
    }

    @Test
    void 連続して失敗したら残りを打ち切りabortedを立てる() {
        when(comfyUiClient.generateImage(any()))
                .thenAnswer(inv -> images(1))
                .thenThrow(new IllegalStateException("x"))
                .thenThrow(new IllegalStateException("y"));

        BatchOutcome outcome = service.generateBatch(repeated(1, 4), false, (done, total) -> { });

        assertEquals(3, outcome.attemptedRepeats());
        assertEquals(3, outcome.failedRepeats());
        assertTrue(outcome.aborted());
    }
}
