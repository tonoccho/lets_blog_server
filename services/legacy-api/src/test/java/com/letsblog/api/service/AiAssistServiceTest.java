package com.letsblog.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.ai.AiProvider;
import com.letsblog.api.ai.BraveSearchResult;
import com.letsblog.api.ai.ChatGptImageClient;
import com.letsblog.api.ai.ComfyUiClient;
import com.letsblog.api.ai.ComfyUiGenerationParams;
import com.letsblog.api.ai.ComfyUiImage;
import com.letsblog.api.ai.ImageProvider;
import com.letsblog.api.ai.LlmClient;
import com.letsblog.api.ai.MediaGeneratedImageClient;
import com.letsblog.api.domain.GenerationJob;
import com.letsblog.api.dto.AiAskRequest;
import com.letsblog.api.dto.AiAskResponse;
import com.letsblog.api.dto.AiDraftRequest;
import com.letsblog.api.dto.AiDraftResponse;
import com.letsblog.api.dto.AiImageBatchResponse;
import com.letsblog.api.dto.AiImagePromptResponse;
import com.letsblog.api.dto.AiImageRequest;
import com.letsblog.api.dto.AiProofreadRequest;
import com.letsblog.api.dto.AiProofreadResponse;
import com.letsblog.api.dto.AiSectionRequest;
import com.letsblog.api.dto.AiTagsRequest;
import com.letsblog.api.dto.AiTagsResponse;
import com.letsblog.api.dto.ImageGenerationOptionsResponse;
import com.letsblog.api.dto.AiSectionResponse;
import com.letsblog.api.dto.PlanChatMessage;
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
    private ChatGptImageClient chatGptImageClient;
    @Mock
    private ImageModelService imageModelService;
    @Mock
    private ComfyUiModelService comfyUiModelService;
    @Mock
    private MediaGeneratedImageClient mediaGeneratedImageClient;
    @Mock
    private GenerationJobRepository generationJobRepository;
    @Mock
    private WebSearchService webSearchService;
    @Mock
    private ProjectService projectService;
    @Mock
    private ArticlePlanService articlePlanService;

    private AiAssistService service;

    private final java.util.concurrent.atomic.AtomicLong nextGeneratedImageId =
            new java.util.concurrent.atomic.AtomicLong(1L);

    @BeforeEach
    void setUp() {
        service = new AiAssistService(llmClient, llmModelService, comfyUiClient, chatGptImageClient,
                imageModelService, comfyUiModelService,
                mediaGeneratedImageClient, generationJobRepository,
                webSearchService, new ObjectMapper(), projectService, new ProhibitedContentFilterService(),
                articlePlanService);

        lenient().when(imageModelService.getSelectedProvider(any())).thenReturn(ImageProvider.COMFYUI);
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
        when(llmClient.generate(anyString())).thenReturn("{\"tags\": [\"猫\", \"かわいい\"]}");

        ArgumentCaptor<String> tagsJsonCaptor = ArgumentCaptor.forClass(String.class);
        service.generateImage(AiImageRequest.withDefaults("a cat"));

        org.mockito.Mockito.verify(llmClient, org.mockito.Mockito.times(1)).generate(anyString());
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
        when(llmClient.generate(anyString())).thenThrow(new RuntimeException("LLM unreachable"));

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
    void ask_検索成功時はsourcesを含み検索結果をプロンプトへ付加する() {
        when(webSearchService.searchSafely(anyString())).thenReturn(
                WebSearchOutcome.success(List.of(new BraveSearchResult("Title", "Desc", "https://example.com"))));
        when(llmClient.generate(anyString(), any(), any())).thenReturn("回答結果");

        AiAskResponse response = service.ask(new AiAskRequest("Next.js 16の新機能は?", null));

        assertEquals("回答結果", response.result());
        assertEquals(1, response.sources().size());
        assertEquals("https://example.com", response.sources().get(0).url());
        assertNull(response.searchNote());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).generate(promptCaptor.capture(), any(), any());
        assertTrue(promptCaptor.getValue().contains("参考のWeb検索結果"));
        assertTrue(promptCaptor.getValue().contains("Next.js 16の新機能は?"));
    }

    @Test
    void ask_検索失敗時はsourcesが空でsearchNoteが設定される() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.failure("APIキー未設定"));
        when(llmClient.generate(anyString(), any(), any())).thenReturn("回答結果");

        AiAskResponse response = service.ask(new AiAskRequest("質問", null));

        assertEquals(List.of(), response.sources());
        assertEquals("Web検索を利用できなかったため、出典なしで生成しています", response.searchNote());
    }

    @Test
    void ask_検索成功だが0件の場合はその旨のsearchNoteになる() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.success(List.of()));
        when(llmClient.generate(anyString(), any(), any())).thenReturn("回答結果");

        AiAskResponse response = service.ask(new AiAskRequest("質問", null));

        assertEquals(List.of(), response.sources());
        assertEquals("関連する検索結果が見つかりませんでした", response.searchNote());
    }

    @Test
    void draft_検索成功時はsourcesを含み検索結果をプロンプトへ付加する() {
        when(webSearchService.searchSafely(anyString())).thenReturn(
                WebSearchOutcome.success(List.of(new BraveSearchResult("Title", "Desc", "https://example.com"))));
        when(llmClient.generate(anyString(), any(), any())).thenReturn("生成結果");

        AiDraftResponse response = service.draft(new AiDraftRequest("draft", "AIブログについて", null));

        assertEquals("生成結果", response.result());
        assertEquals(1, response.sources().size());
        assertEquals("https://example.com", response.sources().get(0).url());
        assertNull(response.searchNote());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).generate(promptCaptor.capture(), any(), any());
        assertTrue(promptCaptor.getValue().contains("参考のWeb検索結果"));
    }

    @Test
    void draft_検索失敗時はsourcesが空でsearchNoteが設定される() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.failure("APIキー未設定"));
        when(llmClient.generate(anyString(), any(), any())).thenReturn("生成結果");

        AiDraftResponse response = service.draft(new AiDraftRequest("draft", "AIブログについて", null));

        assertEquals(List.of(), response.sources());
        assertEquals("Web検索を利用できなかったため、出典なしで生成しています", response.searchNote());
    }

    @Test
    void draft_検索成功だが0件の場合はその旨のsearchNoteになる() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.success(List.of()));
        when(llmClient.generate(anyString(), any(), any())).thenReturn("生成結果");

        AiDraftResponse response = service.draft(new AiDraftRequest("draft", "AIブログについて", null));

        assertEquals(List.of(), response.sources());
        assertEquals("関連する検索結果が見つかりませんでした", response.searchNote());
    }

    @Test
    void draft_不正なmodeは例外() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.draft(new AiDraftRequest("invalid", "text", null)));
    }

    @Test
    void generateSection_本文モードは直前の文脈を含むプロンプトを組み立てる() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.failure("未設定"));
        when(llmClient.generate(anyString(), any(), any())).thenReturn("セクション本文");

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("body", "導入部", "前の段落の文脈", "記事タイトル", null, null, null, null));

        assertEquals("セクション本文", response.result());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).generate(promptCaptor.capture(), any(), any());
        assertTrue(promptCaptor.getValue().contains("前の段落の文脈"));
        assertTrue(promptCaptor.getValue().contains("記事タイトル"));
        assertTrue(promptCaptor.getValue().contains("導入部"));
    }

    @Test
    void generateSection_リード文モードは出典を含めて返す() {
        when(webSearchService.searchSafely(anyString())).thenReturn(
                WebSearchOutcome.success(List.of(new BraveSearchResult("Title", "Desc", "https://example.com"))));
        when(llmClient.generate(anyString(), any(), any())).thenReturn("リード文");

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("lead", null, null, "記事タイトル", List.of("導入", "本編", "まとめ"), null, null, null));

        assertEquals("リード文", response.result());
        assertEquals(1, response.sources().size());
        assertNull(response.searchNote());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).generate(promptCaptor.capture(), any(), any());
        assertTrue(promptCaptor.getValue().contains("導入"));
        assertTrue(promptCaptor.getValue().contains("まとめ"));
    }

    @Test
    void generateSection_サブセクション考慮モードは見出しとサブセクション一覧を含むプロンプトを組み立てる() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.failure("未設定"));
        when(llmClient.generate(anyString(), any(), any())).thenReturn("セクションリード文");

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("lead-subsections", "第2章 実装編", null, "記事タイトル",
                        List.of("設計", "実装", "テスト"), null, null, null));

        assertEquals("セクションリード文", response.result());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).generate(promptCaptor.capture(), any(), any());
        assertTrue(promptCaptor.getValue().contains("第2章 実装編"));
        assertTrue(promptCaptor.getValue().contains("設計"));
        assertTrue(promptCaptor.getValue().contains("テスト"));
    }

    @Test
    void generateSection_messageが指定されると壁打ち形式のプロンプトを組み立てる() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.failure("未設定"));
        when(llmClient.generate(anyString(), any(), any())).thenReturn("再生成された本文");

        List<PlanChatMessage> history = List.of(
                new PlanChatMessage("assistant", "1回目の生成結果"));

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("body", "導入部", "前の段落の文脈", "記事タイトル", null,
                        history, "もっと短くして", null));

        assertEquals("再生成された本文", response.result());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).generate(promptCaptor.capture(), any(), any());
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("System:"));
        assertTrue(prompt.contains("1回目の生成結果"));
        assertTrue(prompt.contains("もっと短くして"));
        assertTrue(prompt.trim().endsWith("Assistant:"));
    }

    @Test
    void generateSection_不正なmodeは例外() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.generateSection(
                        new AiSectionRequest("invalid", "見出し", null, null, null, null, null, null)));
    }

    @Test
    void generateImagePrompt_プロジェクトの選択モデルでシステムプロンプトと履歴を含めて生成する() {
        when(llmModelService.getSelectedModel(1L)).thenReturn("llama3");
        when(llmClient.generate(anyString(), org.mockito.ArgumentMatchers.eq("llama3"), any()))
                .thenReturn("a cute cat, studio lighting, high quality");

        List<PlanChatMessage> history = List.of(new PlanChatMessage("user", "猫の画像がほしい"));

        AiImagePromptResponse response = service.generateImagePrompt(1L, history, "もっと可愛くして", null);

        assertEquals("a cute cat, studio lighting, high quality", response.prompt());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient)
                .generate(promptCaptor.capture(), org.mockito.ArgumentMatchers.eq("llama3"), any());
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("System:"));
        assertTrue(prompt.contains("猫の画像がほしい"));
        assertTrue(prompt.contains("もっと可愛くして"));
        assertTrue(prompt.trim().endsWith("Assistant:"));
    }

    @Test
    void generateImagePrompt_履歴がnullでも生成できる() {
        when(llmModelService.getSelectedModel(2L)).thenReturn("llama3");
        when(llmClient.generate(anyString(), anyString(), any())).thenReturn("a mountain landscape");

        AiImagePromptResponse response = service.generateImagePrompt(2L, null, "山の風景", null);

        assertEquals("a mountain landscape", response.prompt());
    }

    @Test
    void generateImagePrompt_リクエストのproviderがプロジェクト既定より優先される() {
        when(llmModelService.getSelectedModel(1L)).thenReturn("llama3");
        when(llmClient.generate(anyString(), anyString(), org.mockito.ArgumentMatchers.eq(AiProvider.CLAUDE)))
                .thenReturn("a cute cat");

        AiImagePromptResponse response = service.generateImagePrompt(1L, List.of(), "猫", "claude");

        assertEquals("a cute cat", response.prompt());
        org.mockito.Mockito.verify(llmClient)
                .generate(anyString(), anyString(), org.mockito.ArgumentMatchers.eq(AiProvider.CLAUDE));
        org.mockito.Mockito.verify(llmModelService, org.mockito.Mockito.never()).getSelectedProvider(any());
    }

    @Test
    void generateImagePrompt_LLM呼び出し失敗時はジョブを失敗として記録し例外を伝播する() {
        when(llmModelService.getSelectedModel(1L)).thenReturn("llama3");
        when(llmClient.generate(anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("接続エラー"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service.generateImagePrompt(1L, List.of(), "犬の画像", null));
    }

    @Test
    void suggestTags_projectId未指定なら既存タグを問い合わせずプロンプトはそのまま() {
        when(llmClient.generate(anyString(), any(), any()))
                .thenReturn("{\"categories\": [\"技術\"], \"tags\": [\"Java\", \"Spring\"]}");

        AiTagsResponse response = service.suggestTags(new AiTagsRequest("記事本文", null, null));

        assertEquals(List.of("技術"), response.categories());
        assertEquals(List.of("Java", "Spring"), response.tags());
        org.mockito.Mockito.verify(articlePlanService, org.mockito.Mockito.never()).listExistingTags(any());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).generate(promptCaptor.capture(), any(), any());
        assertTrue(promptCaptor.getValue().contains("記事本文"));
        assertTrue(!promptCaptor.getValue().contains("既存タグ一覧"));
    }

    @Test
    void suggestTags_既存タグがあればプロンプトへ追加され結果も既存タグ優先で並び替えられる() {
        when(articlePlanService.listExistingTags(1L)).thenReturn(List.of("Java", "AWS"));
        when(llmClient.generate(anyString(), any(), any()))
                .thenReturn("{\"categories\": [], \"tags\": [\"Kotlin\", \"Java\", \"AWS\", \"Docker\"]}");

        AiTagsResponse response = service.suggestTags(new AiTagsRequest("記事本文", null, 1L));

        assertEquals(List.of("Java", "AWS", "Kotlin", "Docker"), response.tags());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).generate(promptCaptor.capture(), any(), any());
        assertTrue(promptCaptor.getValue().contains("既存タグ一覧"));
        assertTrue(promptCaptor.getValue().contains("Java, AWS"));
    }

    @Test
    void suggestTags_不正なJSON応答は空のcategories_tagsにフォールバックする() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn("これはJSONではありません");

        AiTagsResponse response = service.suggestTags(new AiTagsRequest("記事本文", null, null));

        assertEquals(List.of(), response.categories());
        assertEquals(List.of(), response.tags());
    }

    @Test
    void proofreadContent_正常なJSON配列応答を指摘一覧として返す() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn(
                "[{\"type\": \"typo\", \"originalText\": \"こんちには\", "
                        + "\"message\": \"誤字です\", \"suggestion\": \"こんにちは\"}]");

        AiProofreadResponse response = service.proofreadContent(new AiProofreadRequest("こんちには世界", null));

        assertEquals(1, response.issues().size());
        assertEquals("typo", response.issues().get(0).type());
        assertEquals("こんちには", response.issues().get(0).originalText());
        assertEquals("誤字です", response.issues().get(0).message());
        assertEquals("こんにちは", response.issues().get(0).suggestion());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).generate(promptCaptor.capture(), any(), any());
        assertTrue(promptCaptor.getValue().contains("こんちには世界"));
    }

    @Test
    void proofreadContent_本文に存在しないoriginalTextの指摘は除外する() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn(
                "[{\"type\": \"typo\", \"originalText\": \"本文に無い文字列\", "
                        + "\"message\": \"誤字です\", \"suggestion\": null}]");

        AiProofreadResponse response = service.proofreadContent(new AiProofreadRequest("こんにちは世界", null));

        assertEquals(List.of(), response.issues());
    }

    @Test
    void proofreadContent_不正なJSON応答は空の指摘一覧にフォールバックする() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn("これはJSONではありません");

        AiProofreadResponse response = service.proofreadContent(new AiProofreadRequest("こんにちは世界", null));

        assertEquals(List.of(), response.issues());
    }
}
