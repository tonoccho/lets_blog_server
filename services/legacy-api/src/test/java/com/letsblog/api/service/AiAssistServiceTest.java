package com.letsblog.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.ai.ChatGptImageClient;
import com.letsblog.api.ai.ComfyUiClient;
import com.letsblog.api.ai.ComfyUiGenerationParams;
import com.letsblog.api.ai.ComfyUiImage;
import com.letsblog.api.ai.ImageProvider;
import com.letsblog.api.ai.MediaGeneratedImageClient;
import com.letsblog.api.client.AiGenerationClient;
import com.letsblog.api.client.GenerationJobClient;
import com.letsblog.api.client.GenerationJobSummary;
import com.letsblog.api.dto.AiImageBatchResponse;
import com.letsblog.api.dto.AiImagePromptResponse;
import com.letsblog.api.dto.AiImageRequest;
import com.letsblog.api.dto.ImageGenerationOptionsResponse;
import com.letsblog.api.dto.PlanChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * AiAssistServiceの回帰テスト。issue #574でテキスト生成部分(draft/ask/tags/proofread/section)は
 * ai-serviceへ移設したため、それらの回帰テストはai-service側のAiAssistServiceTestへ移設した。
 * legacy-apiに残った画像生成部分(generateImage/getImageOptions/generateImagePrompt)を検証する。
 * generation_jobsの記録はGenerationJobClient経由のブリッジ呼び出しに、画像生成プロンプト/生成画像の
 * タグ提案のLLM呼び出しはAiGenerationClient経由のブリッジ呼び出しに、それぞれ変わったことを反映している。
 */
@ExtendWith(MockitoExtension.class)
class AiAssistServiceTest {

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
    private MediaGeneratedImageClient mediaGeneratedImageClient;
    @Mock
    private GenerationJobClient generationJobClient;
    @Mock
    private ProjectService projectService;

    private AiAssistService service;

    private final AtomicLong nextGeneratedImageId = new AtomicLong(1L);
    private final AtomicLong nextJobId = new AtomicLong(1L);

    @BeforeEach
    void setUp() {
        service = new AiAssistService(aiGenerationClient, comfyUiClient, chatGptImageClient,
                imageModelService, comfyUiModelService,
                mediaGeneratedImageClient, generationJobClient,
                new ObjectMapper(), projectService, new ProhibitedContentFilterService());

        lenient().when(imageModelService.getSelectedProvider(any())).thenReturn(ImageProvider.COMFYUI);
        lenient().when(projectService.resolveDefaultNegativePrompt(any()))
                .thenReturn("low quality, blurry, watermark, text");
        lenient().when(projectService.resolveDefaultQualityPrompt(any())).thenReturn("");
        lenient().when(generationJobClient.create(any(), any())).thenAnswer(inv -> new GenerationJobSummary(
                nextJobId.getAndIncrement(), inv.getArgument(0), "running", LocalDateTime.now(), LocalDateTime.now()));
        lenient().when(mediaGeneratedImageClient.create(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                        any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> nextGeneratedImageId.getAndIncrement());
    }

    @Test
    void generateImage_batch_sizeが4の場合は4枚分保存し4枚分のレスポンスを返す() {
        List<ComfyUiImage> images = List.of(
                new ComfyUiImage("a.png", new byte[]{1}, "image/png"),
                new ComfyUiImage("b.png", new byte[]{2}, "image/png"),
                new ComfyUiImage("c.png", new byte[]{3}, "image/png"),
                new ComfyUiImage("d.png", new byte[]{4}, "image/png"));
        when(comfyUiClient.generateImage(any())).thenReturn(images);
        when(comfyUiModelService.getSelectedCheckpointOrGlobalDefault(any())).thenReturn("checkpoint.safetensors");

        AiImageBatchResponse response = service.generateImage(AiImageRequest.withDefaults("a cat"));

        assertEquals(4, response.images().size());
        assertEquals("a.png", response.images().get(0).fileName());
        assertEquals("d.png", response.images().get(3).fileName());
        org.mockito.Mockito.verify(comfyUiClient).generateImage(any());
        org.mockito.Mockito.verifyNoInteractions(chatGptImageClient);
        org.mockito.Mockito.verify(mediaGeneratedImageClient, org.mockito.Mockito.times(4)).create(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void generateImage_プロジェクトの画像生成AIがCHATGPTの場合はChatGptImageClientを使いproviderをCHATGPTで保存する() {
        when(imageModelService.getSelectedProvider(1L)).thenReturn(ImageProvider.CHATGPT);
        when(chatGptImageClient.generateImage(any())).thenReturn(
                List.of(new ComfyUiImage("chatgpt_1.png", new byte[]{1}, "image/png")));

        AiImageRequest request = new AiImageRequest(
                "a cat", null, null, null, null, null, null, null, null, null, null, null, null, 1L);
        service.generateImage(request);

        org.mockito.Mockito.verify(chatGptImageClient).generateImage(any());
        org.mockito.Mockito.verifyNoInteractions(comfyUiClient);
        ArgumentCaptor<String> providerCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(mediaGeneratedImageClient).create(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), providerCaptor.capture(), any(), any());
        assertEquals("CHATGPT", providerCaptor.getValue());
    }

    @Test
    void generateImage_プロジェクトの画像生成AIが未選択の場合はComfyUiClientを使いproviderをCOMFYUIで保存する() {
        when(comfyUiClient.generateImage(any())).thenReturn(
                List.of(new ComfyUiImage("a.png", new byte[]{1}, "image/png")));
        when(comfyUiModelService.getSelectedCheckpointOrGlobalDefault(any())).thenReturn("checkpoint.safetensors");

        service.generateImage(AiImageRequest.withDefaults("a cat"));

        ArgumentCaptor<String> providerCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(mediaGeneratedImageClient).create(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), providerCaptor.capture(), any(), any());
        assertEquals("COMFYUI", providerCaptor.getValue());
    }

    @Test
    void generateImage_性的コンテンツ禁止設定が有効で該当キーワードを含む場合は例外をスローしプロバイダーを呼ばない() {
        when(comfyUiModelService.getSelectedCheckpointOrGlobalDefault(any())).thenReturn("checkpoint.safetensors");
        when(projectService.resolveBlockSexualContent(any())).thenReturn(true);

        AiImageRequest request = AiImageRequest.withDefaults("nude portrait");
        org.junit.jupiter.api.Assertions.assertThrows(
                ProhibitedContentException.class, () -> service.generateImage(request));

        org.mockito.Mockito.verifyNoInteractions(comfyUiClient);
        org.mockito.Mockito.verifyNoInteractions(chatGptImageClient);
    }

    @Test
    void generateImage_不適切コンテンツ設定が無効なら該当キーワードを含んでいても生成できる() {
        when(comfyUiClient.generateImage(any())).thenReturn(
                List.of(new ComfyUiImage("a.png", new byte[]{1}, "image/png")));
        when(comfyUiModelService.getSelectedCheckpointOrGlobalDefault(any())).thenReturn("checkpoint.safetensors");
        when(projectService.resolveBlockSexualContent(any())).thenReturn(false);

        AiImageBatchResponse response = service.generateImage(AiImageRequest.withDefaults("nude portrait"));

        assertEquals(1, response.images().size());
        org.mockito.Mockito.verify(comfyUiClient).generateImage(any());
    }

    @Test
    void generateImage_タグ提案はバッチにつき1回だけ呼ばれ全画像へ適用される() {
        List<ComfyUiImage> images = List.of(
                new ComfyUiImage("a.png", new byte[]{1}, "image/png"),
                new ComfyUiImage("b.png", new byte[]{2}, "image/png"));
        when(comfyUiClient.generateImage(any())).thenReturn(images);
        when(comfyUiModelService.getSelectedCheckpointOrGlobalDefault(any())).thenReturn("checkpoint.safetensors");
        when(aiGenerationClient.generate(isNull(), anyString(), isNull()))
                .thenReturn("{\"tags\": [\"猫\", \"かわいい\"]}");

        ArgumentCaptor<String> tagsJsonCaptor = ArgumentCaptor.forClass(String.class);
        service.generateImage(AiImageRequest.withDefaults("a cat"));

        org.mockito.Mockito.verify(aiGenerationClient, org.mockito.Mockito.times(1))
                .generate(isNull(), anyString(), isNull());
        org.mockito.Mockito.verify(mediaGeneratedImageClient, org.mockito.Mockito.times(2)).create(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), tagsJsonCaptor.capture(), any());
        for (String tagsJson : tagsJsonCaptor.getAllValues()) {
            assertTrue(tagsJson.contains("猫"));
            assertTrue(tagsJson.contains("かわいい"));
        }
    }

    @Test
    void generateImage_タグ提案に失敗しても画像生成自体は続行する() {
        when(comfyUiClient.generateImage(any())).thenReturn(
                List.of(new ComfyUiImage("a.png", new byte[]{1}, "image/png")));
        when(comfyUiModelService.getSelectedCheckpointOrGlobalDefault(any())).thenReturn("checkpoint.safetensors");
        when(aiGenerationClient.generate(isNull(), anyString(), isNull()))
                .thenThrow(new RuntimeException("LLM unreachable"));

        ArgumentCaptor<String> tagsJsonCaptor = ArgumentCaptor.forClass(String.class);
        AiImageBatchResponse response = service.generateImage(AiImageRequest.withDefaults("a cat"));

        assertEquals(1, response.images().size());
        org.mockito.Mockito.verify(mediaGeneratedImageClient).create(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), tagsJsonCaptor.capture(), any());
        assertNull(tagsJsonCaptor.getValue());
    }

    @Test
    void generateImage_画質プロンプトを本文プロンプトへ付加しnegative_prompt未指定時はプロジェクト解決値を使う() {
        when(comfyUiClient.generateImage(any())).thenReturn(
                List.of(new ComfyUiImage("a.png", new byte[]{1}, "image/png")));
        when(comfyUiModelService.getSelectedCheckpointOrGlobalDefault(any())).thenReturn("checkpoint.safetensors");
        when(projectService.resolveDefaultQualityPrompt(any())).thenReturn("high quality, detailed");
        when(projectService.resolveDefaultNegativePrompt(any())).thenReturn("worst quality");

        service.generateImage(AiImageRequest.withDefaults("a cat"));

        ArgumentCaptor<ComfyUiGenerationParams> captor = ArgumentCaptor.forClass(ComfyUiGenerationParams.class);
        org.mockito.Mockito.verify(comfyUiClient).generateImage(captor.capture());
        assertEquals("a cat, high quality, detailed", captor.getValue().prompt());
        assertEquals("worst quality", captor.getValue().negativePrompt());
    }

    @Test
    void generateImage_リクエストにnegative_promptがあればプロジェクト解決値より優先する() {
        when(comfyUiClient.generateImage(any())).thenReturn(
                List.of(new ComfyUiImage("a.png", new byte[]{1}, "image/png")));
        when(comfyUiModelService.getSelectedCheckpointOrGlobalDefault(any())).thenReturn("checkpoint.safetensors");
        AiImageRequest request = new AiImageRequest(
                "a cat", "custom negative", null, null, null, null, null, null, null, null, null, null, null, null);

        service.generateImage(request);

        ArgumentCaptor<ComfyUiGenerationParams> captor = ArgumentCaptor.forClass(ComfyUiGenerationParams.class);
        org.mockito.Mockito.verify(comfyUiClient).generateImage(captor.capture());
        assertEquals("custom negative", captor.getValue().negativePrompt());
        org.mockito.Mockito.verify(projectService, org.mockito.Mockito.never()).resolveDefaultNegativePrompt(any());
    }

    @Test
    void generateImage_widthとheightの未指定分はプロジェクト解決値を使う() {
        when(comfyUiClient.generateImage(any())).thenReturn(
                List.of(new ComfyUiImage("a.png", new byte[]{1}, "image/png")));
        when(comfyUiModelService.getSelectedCheckpointOrGlobalDefault(any())).thenReturn("checkpoint.safetensors");
        when(projectService.resolveDefaultGeneratedImageWidth(any())).thenReturn(1920);
        when(projectService.resolveDefaultGeneratedImageHeight(any())).thenReturn(1080);

        service.generateImage(AiImageRequest.withDefaults("a cat"));

        ArgumentCaptor<ComfyUiGenerationParams> captor = ArgumentCaptor.forClass(ComfyUiGenerationParams.class);
        org.mockito.Mockito.verify(comfyUiClient).generateImage(captor.capture());
        assertEquals(1920, captor.getValue().width());
        assertEquals(1080, captor.getValue().height());
    }

    @Test
    void getImageOptions_プロジェクトのデフォルトサイズを含む() {
        when(comfyUiModelService.getSelectedCheckpointOrGlobalDefault(1L)).thenReturn("checkpoint.safetensors");
        when(comfyUiClient.listCheckpoints()).thenReturn(List.of("checkpoint.safetensors"));
        when(comfyUiClient.listSamplers()).thenReturn(List.of("euler"));
        when(comfyUiClient.listSchedulers()).thenReturn(List.of("normal"));
        when(comfyUiClient.listLoras()).thenReturn(List.of());
        when(projectService.resolveDefaultGeneratedImageWidth(1L)).thenReturn(1920);
        when(projectService.resolveDefaultGeneratedImageHeight(1L)).thenReturn(1080);

        ImageGenerationOptionsResponse response = service.getImageOptions(1L);

        assertEquals(1920, response.defaultWidth());
        assertEquals(1080, response.defaultHeight());
    }

    @Test
    void getImageOptions_実際の生成時と同じ既定negative_quality_promptを含む() {
        when(comfyUiModelService.getSelectedCheckpointOrGlobalDefault(1L)).thenReturn("checkpoint.safetensors");
        when(comfyUiClient.listCheckpoints()).thenReturn(List.of("checkpoint.safetensors"));
        when(comfyUiClient.listSamplers()).thenReturn(List.of("euler"));
        when(comfyUiClient.listSchedulers()).thenReturn(List.of("normal"));
        when(comfyUiClient.listLoras()).thenReturn(List.of());
        when(projectService.resolveDefaultGeneratedImageWidth(1L)).thenReturn(1920);
        when(projectService.resolveDefaultGeneratedImageHeight(1L)).thenReturn(1080);
        when(projectService.resolveDefaultNegativePrompt(1L)).thenReturn("worst quality");
        when(projectService.resolveDefaultQualityPrompt(1L)).thenReturn("high quality, detailed");

        ImageGenerationOptionsResponse response = service.getImageOptions(1L);

        assertEquals("worst quality", response.defaultNegativePrompt());
        assertEquals("high quality, detailed", response.defaultQualityPrompt());
    }

    @Test
    void generateImagePrompt_ai_serviceへ委譲しシステムプロンプトと履歴を含めたプロンプトを渡す() {
        when(aiGenerationClient.generate(eq(1L), anyString(), isNull()))
                .thenReturn("a cute cat, studio lighting, high quality");

        List<PlanChatMessage> history = List.of(new PlanChatMessage("user", "猫の画像がほしい"));

        AiImagePromptResponse response = service.generateImagePrompt(1L, history, "もっと可愛くして", null);

        assertEquals("a cute cat, studio lighting, high quality", response.prompt());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(aiGenerationClient).generate(eq(1L), promptCaptor.capture(), isNull());
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("System:"));
        assertTrue(prompt.contains("猫の画像がほしい"));
        assertTrue(prompt.contains("もっと可愛くして"));
        assertTrue(prompt.trim().endsWith("Assistant:"));
    }

    @Test
    void generateImagePrompt_履歴がnullでも生成できる() {
        when(aiGenerationClient.generate(eq(2L), anyString(), isNull())).thenReturn("a mountain landscape");

        AiImagePromptResponse response = service.generateImagePrompt(2L, null, "山の風景", null);

        assertEquals("a mountain landscape", response.prompt());
    }

    @Test
    void generateImagePrompt_リクエストのproviderがai_serviceへそのまま渡される() {
        when(aiGenerationClient.generate(eq(1L), anyString(), eq("claude"))).thenReturn("a cute cat");

        AiImagePromptResponse response = service.generateImagePrompt(1L, List.of(), "猫", "claude");

        assertEquals("a cute cat", response.prompt());
        org.mockito.Mockito.verify(aiGenerationClient).generate(eq(1L), anyString(), eq("claude"));
    }

    @Test
    void generateImagePrompt_LLM呼び出し失敗時はジョブを失敗として記録し例外を伝播する() {
        when(aiGenerationClient.generate(eq(1L), anyString(), isNull()))
                .thenThrow(new RuntimeException("接続エラー"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service.generateImagePrompt(1L, List.of(), "犬の画像", null));

        org.mockito.Mockito.verify(generationJobClient).updateStatus(any(), eq("failed"), any());
    }
}
