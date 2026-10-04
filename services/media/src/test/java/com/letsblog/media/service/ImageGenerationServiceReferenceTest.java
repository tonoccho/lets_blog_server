package com.letsblog.media.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.common.client.GenerationJobSummary;
import com.letsblog.media.ai.ChatGptImageClient;
import com.letsblog.media.ai.ComfyUiClient;
import com.letsblog.media.ai.ComfyUiGenerationParams;
import com.letsblog.media.ai.ComfyUiImage;
import com.letsblog.media.ai.ImageProvider;
import com.letsblog.media.ai.ReferenceImage;
import com.letsblog.media.ai.SeedResolver;
import com.letsblog.media.client.AiGenerationClient;
import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.dto.AiImageRequest;
import com.letsblog.media.dto.CreateGeneratedImageRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 参照画像付き(img2img)生成のオーケストレーション(issue #1601)。
 *
 * <p>参照画像は、ジョブを作る前に検証する(削除済み・他プロジェクトは拒否、ChatGPTは未対応で拒否)。
 * 受理された要求では、参照画像のバイト列とdenoiseがComfyUIへ渡り、生成した各画像の行に
 * 参照元の画像IDが残る。参照画像が無い要求は従来と同じ(参照・denoiseとも空)。
 *
 * <p>Gherkin側(media/image-reference-generation.feature)が画面とAPIから同じ受け入れ基準を見る。
 * ここは分岐(プロバイダ・同期/非同期・リピート)を網羅する。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("media-service: 参照画像付き生成(issue #1601)")
class ImageGenerationServiceReferenceTest {

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
    private ReferenceImageService referenceImageService;
    @Mock
    private HttpServletRequest request;

    private ImageGenerationService service;

    private static final ReferenceImage REFERENCE = new ReferenceImage(new byte[] {4, 5, 6}, "image/png");

    @BeforeEach
    void setUp() {
        service = new ImageGenerationService(
                aiGenerationClient, comfyUiClient, chatGptImageClient, imageModelService, comfyUiModelService,
                generatedImageCreationService, generationJobClient, new ObjectMapper(), defaultsResolver,
                prohibitedContentFilterService, new SafetyNegativePromptService("a", "b", "c"),
                new SeedResolver(), referenceImageService, request);
        when(imageModelService.getSelectedProvider(any())).thenReturn(ImageProvider.COMFYUI);
        when(comfyUiModelService.getSelectedCheckpointOrGlobalDefault(any())).thenReturn("g.safetensors");
        when(defaultsResolver.resolveDefaultGeneratedImageWidth(any())).thenReturn(512);
        when(defaultsResolver.resolveDefaultGeneratedImageHeight(any())).thenReturn(512);
        when(aiGenerationClient.generate(any(), anyString(), any())).thenReturn("{\"tags\":[]}");
        when(generationJobClient.create(anyString(), anyString(), any()))
                .thenReturn(new GenerationJobSummary(9L, "comfyui_image", "running", null, null));
        when(generatedImageCreationService.create(any())).thenAnswer(inv -> {
            GeneratedImage saved = new GeneratedImage();
            saved.setId(100L);
            return saved;
        });
        when(comfyUiClient.generateImage(any())).thenAnswer(inv -> List.of(
                new ComfyUiImage("a.png", new byte[] {1}, "image/png")));
        when(referenceImageService.load(1L, 5L)).thenReturn(REFERENCE);
    }

    private static AiImageRequest withReference(Long projectId, Long referenceImageId, Double denoise) {
        return new AiImageRequest(
                "a cat", null, null, null, null, null, 1L, null, null, null, null, null, null, null,
                projectId, referenceImageId, denoise);
    }

    private ComfyUiGenerationParams comfyParams() {
        ArgumentCaptor<ComfyUiGenerationParams> captor = ArgumentCaptor.forClass(ComfyUiGenerationParams.class);
        verify(comfyUiClient).generateImage(captor.capture());
        return captor.getValue();
    }

    private CreateGeneratedImageRequest savedRequest() {
        ArgumentCaptor<CreateGeneratedImageRequest> captor =
                ArgumentCaptor.forClass(CreateGeneratedImageRequest.class);
        verify(generatedImageCreationService).create(captor.capture());
        return captor.getValue();
    }

    @Test
    void 同期生成は参照画像のバイト列とdenoiseをComfyUIへ渡し参照元IDを保存する() {
        service.generateImage(withReference(1L, 5L, 0.3));

        ComfyUiGenerationParams params = comfyParams();
        assertSame(REFERENCE, params.referenceImage());
        assertEquals(0.3, params.denoise());
        assertEquals(5L, savedRequest().sourceImageId());
        verify(referenceImageService).requireUsable(1L, 5L);
    }

    @Test
    void denoise未指定ならそのままnullを渡しComfyUIClientの既定に任せる() {
        service.generateImage(withReference(1L, 5L, null));

        assertNull(comfyParams().denoise());
    }

    @Test
    void 参照画像が無い要求は従来どおり参照もdenoiseも空で保存にも参照元は無い() {
        service.generateImage(withReference(1L, null, null));

        ComfyUiGenerationParams params = comfyParams();
        assertNull(params.referenceImage());
        assertNull(params.denoise());
        assertNull(savedRequest().sourceImageId());
        verify(referenceImageService, never()).requireUsable(any(), any());
        verify(referenceImageService, never()).load(any(), any());
    }

    @Test
    void 参照画像が使えない要求はジョブを作らず生成もしない_同期() {
        when(referenceImageService.requireUsable(1L, 5L)).thenThrow(new InvalidReferenceImageException("x"));

        assertThrows(InvalidReferenceImageException.class,
                () -> service.generateImage(withReference(1L, 5L, null)));

        verify(generationJobClient, never()).create(anyString(), anyString(), any());
        verify(comfyUiClient, never()).generateImage(any());
    }

    @Test
    void ChatGPTでの参照画像付き要求は未対応として拒否しジョブを作らない_同期() {
        when(imageModelService.getSelectedProvider(any())).thenReturn(ImageProvider.CHATGPT);

        UnsupportedReferenceImageException e = assertThrows(UnsupportedReferenceImageException.class,
                () -> service.generateImage(withReference(1L, 5L, null)));

        assertTrue(e.getMessage().contains("未対応"), e.getMessage());
        verify(generationJobClient, never()).create(anyString(), anyString(), any());
        verify(chatGptImageClient, never()).generateImage(any());
    }

    @Test
    void 非同期の受理前検証でも参照画像を確かめる() {
        ImageProvider provider = service.requireAcceptable(withReference(1L, 5L, null));

        assertEquals(ImageProvider.COMFYUI, provider);
        verify(referenceImageService).requireUsable(1L, 5L);
    }

    @Test
    void 非同期の受理前検証で他プロジェクトの参照画像は拒否する() {
        when(referenceImageService.requireUsable(1L, 5L)).thenThrow(new InvalidReferenceImageException("x"));

        assertThrows(InvalidReferenceImageException.class,
                () -> service.requireAcceptable(withReference(1L, 5L, null)));
    }

    @Test
    void 非同期の受理前検証でChatGPTの参照画像付き要求は未対応で拒否する() {
        when(imageModelService.getSelectedProvider(any())).thenReturn(ImageProvider.CHATGPT);

        assertThrows(UnsupportedReferenceImageException.class,
                () -> service.requireAcceptable(withReference(1L, 5L, null)));

        verify(referenceImageService, never()).requireUsable(any(), any());
    }

    @Test
    void 非同期の受理前検証は参照画像が無ければ何も確かめない() {
        service.requireAcceptable(withReference(1L, null, null));

        verify(referenceImageService, never()).requireUsable(any(), any());
    }

    @Test
    void 非同期のgenerateBatchでも参照画像とdenoiseを渡し参照元IDを残す() {
        service.generateBatch(withReference(1L, 5L, 0.9), false, (done, total) -> { });

        assertSame(REFERENCE, comfyParams().referenceImage());
        assertEquals(0.9, comfyParams().denoise());
        assertEquals(5L, savedRequest().sourceImageId());
    }

    @Test
    void 参照画像はリピートの数によらず1回だけ読み込み全リピートへ渡す() {
        AiImageRequest repeated = new AiImageRequest(
                "a cat", null, null, null, null, null, 1L, null, null, null, 3, null, null, null,
                1L, 5L, 0.4);

        service.generateBatch(repeated, false, (done, total) -> { });

        verify(referenceImageService, times(1)).load(1L, 5L);
        ArgumentCaptor<ComfyUiGenerationParams> captor = ArgumentCaptor.forClass(ComfyUiGenerationParams.class);
        verify(comfyUiClient, times(3)).generateImage(captor.capture());
        for (ComfyUiGenerationParams params : captor.getAllValues()) {
            assertSame(REFERENCE, params.referenceImage());
            assertEquals(0.4, params.denoise());
        }
    }
}
