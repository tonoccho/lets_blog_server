package com.letsblog.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.ai.BraveSearchResult;
import com.letsblog.api.ai.ComfyUiClient;
import com.letsblog.api.ai.ComfyUiGenerationParams;
import com.letsblog.api.ai.ComfyUiImage;
import com.letsblog.api.ai.GeneratedImageStorageService;
import com.letsblog.api.ai.LlmClient;
import com.letsblog.api.domain.GeneratedImage;
import com.letsblog.api.domain.GenerationJob;
import com.letsblog.api.dto.AiDraftRequest;
import com.letsblog.api.dto.AiDraftResponse;
import com.letsblog.api.dto.AiImageBatchResponse;
import com.letsblog.api.dto.AiImagePromptResponse;
import com.letsblog.api.dto.AiImageRequest;
import com.letsblog.api.dto.AiSectionRequest;
import com.letsblog.api.dto.ImageGenerationOptionsResponse;
import com.letsblog.api.dto.AiSectionResponse;
import com.letsblog.api.dto.PlanChatMessage;
import com.letsblog.api.repository.GeneratedImageRepository;
import com.letsblog.api.repository.GenerationJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * AiAssistServiceの回帰テスト。draft()/generateSection()へのBrave出典統合
 * (検索成功/失敗/0件それぞれでsources・searchNoteが期待通りになること)を中心に検証する。
 */
@ExtendWith(MockitoExtension.class)
class AiAssistServiceTest {

    @Mock
    private LlmClient llmClient;
    @Mock
    private LlmModelService llmModelService;
    @Mock
    private ComfyUiClient comfyUiClient;
    @Mock
    private ComfyUiModelService comfyUiModelService;
    @Mock
    private GeneratedImageStorageService generatedImageStorageService;
    @Mock
    private GeneratedImageRepository generatedImageRepository;
    @Mock
    private GenerationJobRepository generationJobRepository;
    @Mock
    private WebSearchService webSearchService;
    @Mock
    private ProjectService projectService;

    private AiAssistService service;

    @BeforeEach
    void setUp() {
        service = new AiAssistService(llmClient, llmModelService, comfyUiClient, comfyUiModelService,
                generatedImageStorageService, generatedImageRepository, generationJobRepository,
                webSearchService, new ObjectMapper(), projectService);

        lenient().when(projectService.resolveDefaultNegativePrompt(any()))
                .thenReturn("low quality, blurry, watermark, text");
        lenient().when(projectService.resolveDefaultQualityPrompt(any())).thenReturn("");
        lenient().when(generationJobRepository.save(any())).thenAnswer(inv -> {
            GenerationJob job = inv.getArgument(0);
            if (job.getId() == null) {
                job.setId(1L);
            }
            return job;
        });
        lenient().when(generatedImageStorageService.store(any(), any())).thenReturn("global/0001.png");
        lenient().when(generatedImageRepository.save(any())).thenAnswer(inv -> {
            GeneratedImage image = inv.getArgument(0);
            image.setId(image.getId() == null ? 1L : image.getId() + 1);
            return image;
        });
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
        org.mockito.Mockito.verify(generatedImageRepository, org.mockito.Mockito.times(4)).save(any());
    }

    @Test
    void generateImage_タグ提案はバッチにつき1回だけ呼ばれ全画像へ適用される() {
        List<ComfyUiImage> images = List.of(
                new ComfyUiImage("a.png", new byte[]{1}, "image/png"),
                new ComfyUiImage("b.png", new byte[]{2}, "image/png"));
        when(comfyUiClient.generateImage(any())).thenReturn(images);
        when(comfyUiModelService.getSelectedCheckpointOrGlobalDefault(any())).thenReturn("checkpoint.safetensors");
        when(llmClient.generate(anyString())).thenReturn("{\"tags\": [\"猫\", \"かわいい\"]}");

        ArgumentCaptor<GeneratedImage> captor = ArgumentCaptor.forClass(GeneratedImage.class);
        service.generateImage(AiImageRequest.withDefaults("a cat"));

        org.mockito.Mockito.verify(llmClient, org.mockito.Mockito.times(1)).generate(anyString());
        org.mockito.Mockito.verify(generatedImageRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        for (GeneratedImage saved : captor.getAllValues()) {
            assertTrue(saved.getTagsJson().contains("猫"));
            assertTrue(saved.getTagsJson().contains("かわいい"));
        }
    }

    @Test
    void generateImage_タグ提案に失敗しても画像生成自体は続行する() {
        when(comfyUiClient.generateImage(any())).thenReturn(
                List.of(new ComfyUiImage("a.png", new byte[]{1}, "image/png")));
        when(comfyUiModelService.getSelectedCheckpointOrGlobalDefault(any())).thenReturn("checkpoint.safetensors");
        when(llmClient.generate(anyString())).thenThrow(new RuntimeException("LLM unreachable"));

        ArgumentCaptor<GeneratedImage> captor = ArgumentCaptor.forClass(GeneratedImage.class);
        AiImageBatchResponse response = service.generateImage(AiImageRequest.withDefaults("a cat"));

        assertEquals(1, response.images().size());
        org.mockito.Mockito.verify(generatedImageRepository).save(captor.capture());
        assertNull(captor.getValue().getTagsJson());
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
    void draft_検索成功時はsourcesを含み検索結果をプロンプトへ付加する() {
        when(webSearchService.searchSafely(anyString())).thenReturn(
                WebSearchOutcome.success(List.of(new BraveSearchResult("Title", "Desc", "https://example.com"))));
        when(llmClient.generate(anyString())).thenReturn("生成結果");

        AiDraftResponse response = service.draft(new AiDraftRequest("draft", "AIブログについて"));

        assertEquals("生成結果", response.result());
        assertEquals(1, response.sources().size());
        assertEquals("https://example.com", response.sources().get(0).url());
        assertNull(response.searchNote());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).generate(promptCaptor.capture());
        assertTrue(promptCaptor.getValue().contains("参考のWeb検索結果"));
    }

    @Test
    void draft_検索失敗時はsourcesが空でsearchNoteが設定される() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.failure("APIキー未設定"));
        when(llmClient.generate(anyString())).thenReturn("生成結果");

        AiDraftResponse response = service.draft(new AiDraftRequest("draft", "AIブログについて"));

        assertEquals(List.of(), response.sources());
        assertEquals("Web検索を利用できなかったため、出典なしで生成しています", response.searchNote());
    }

    @Test
    void draft_検索成功だが0件の場合はその旨のsearchNoteになる() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.success(List.of()));
        when(llmClient.generate(anyString())).thenReturn("生成結果");

        AiDraftResponse response = service.draft(new AiDraftRequest("draft", "AIブログについて"));

        assertEquals(List.of(), response.sources());
        assertEquals("関連する検索結果が見つかりませんでした", response.searchNote());
    }

    @Test
    void draft_不正なmodeは例外() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.draft(new AiDraftRequest("invalid", "text")));
    }

    @Test
    void generateSection_本文モードは直前の文脈を含むプロンプトを組み立てる() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.failure("未設定"));
        when(llmClient.generate(anyString())).thenReturn("セクション本文");

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("body", "導入部", "前の段落の文脈", "記事タイトル", null, null, null));

        assertEquals("セクション本文", response.result());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).generate(promptCaptor.capture());
        assertTrue(promptCaptor.getValue().contains("前の段落の文脈"));
        assertTrue(promptCaptor.getValue().contains("記事タイトル"));
        assertTrue(promptCaptor.getValue().contains("導入部"));
    }

    @Test
    void generateSection_リード文モードは出典を含めて返す() {
        when(webSearchService.searchSafely(anyString())).thenReturn(
                WebSearchOutcome.success(List.of(new BraveSearchResult("Title", "Desc", "https://example.com"))));
        when(llmClient.generate(anyString())).thenReturn("リード文");

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("lead", null, null, "記事タイトル", List.of("導入", "本編", "まとめ"), null, null));

        assertEquals("リード文", response.result());
        assertEquals(1, response.sources().size());
        assertNull(response.searchNote());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).generate(promptCaptor.capture());
        assertTrue(promptCaptor.getValue().contains("導入"));
        assertTrue(promptCaptor.getValue().contains("まとめ"));
    }

    @Test
    void generateSection_サブセクション考慮モードは見出しとサブセクション一覧を含むプロンプトを組み立てる() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.failure("未設定"));
        when(llmClient.generate(anyString())).thenReturn("セクションリード文");

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("lead-subsections", "第2章 実装編", null, "記事タイトル",
                        List.of("設計", "実装", "テスト"), null, null));

        assertEquals("セクションリード文", response.result());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).generate(promptCaptor.capture());
        assertTrue(promptCaptor.getValue().contains("第2章 実装編"));
        assertTrue(promptCaptor.getValue().contains("設計"));
        assertTrue(promptCaptor.getValue().contains("テスト"));
    }

    @Test
    void generateSection_messageが指定されると壁打ち形式のプロンプトを組み立てる() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.failure("未設定"));
        when(llmClient.generate(anyString())).thenReturn("再生成された本文");

        List<PlanChatMessage> history = List.of(
                new PlanChatMessage("assistant", "1回目の生成結果"));

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("body", "導入部", "前の段落の文脈", "記事タイトル", null,
                        history, "もっと短くして"));

        assertEquals("再生成された本文", response.result());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).generate(promptCaptor.capture());
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("System:"));
        assertTrue(prompt.contains("1回目の生成結果"));
        assertTrue(prompt.contains("もっと短くして"));
        assertTrue(prompt.trim().endsWith("Assistant:"));
    }

    @Test
    void generateSection_不正なmodeは例外() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.generateSection(new AiSectionRequest("invalid", "見出し", null, null, null, null, null)));
    }

    @Test
    void generateImagePrompt_プロジェクトの選択モデルでシステムプロンプトと履歴を含めて生成する() {
        when(llmModelService.getSelectedModel(1L)).thenReturn("llama3");
        when(llmClient.generate(anyString(), org.mockito.ArgumentMatchers.eq("llama3")))
                .thenReturn("a cute cat, studio lighting, high quality");

        List<PlanChatMessage> history = List.of(new PlanChatMessage("user", "猫の画像がほしい"));

        AiImagePromptResponse response = service.generateImagePrompt(1L, history, "もっと可愛くして");

        assertEquals("a cute cat, studio lighting, high quality", response.prompt());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).generate(promptCaptor.capture(), org.mockito.ArgumentMatchers.eq("llama3"));
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("System:"));
        assertTrue(prompt.contains("猫の画像がほしい"));
        assertTrue(prompt.contains("もっと可愛くして"));
        assertTrue(prompt.trim().endsWith("Assistant:"));
    }

    @Test
    void generateImagePrompt_履歴がnullでも生成できる() {
        when(llmModelService.getSelectedModel(2L)).thenReturn("llama3");
        when(llmClient.generate(anyString(), anyString())).thenReturn("a mountain landscape");

        AiImagePromptResponse response = service.generateImagePrompt(2L, null, "山の風景");

        assertEquals("a mountain landscape", response.prompt());
    }

    @Test
    void generateImagePrompt_LLM呼び出し失敗時はジョブを失敗として記録し例外を伝播する() {
        when(llmModelService.getSelectedModel(1L)).thenReturn("llama3");
        when(llmClient.generate(anyString(), anyString()))
                .thenThrow(new RuntimeException("接続エラー"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service.generateImagePrompt(1L, List.of(), "犬の画像"));
    }
}
