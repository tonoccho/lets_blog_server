package com.letsblog.media.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.media.ai.ChatGptImageClient;
import com.letsblog.media.ai.ComfyUiClient;
import com.letsblog.media.ai.ComfyUiGenerationParams;
import com.letsblog.media.ai.ComfyUiImage;
import com.letsblog.media.ai.ImageProvider;
import com.letsblog.media.ai.SeedResolver;
import com.letsblog.media.client.AiGenerationClient;
import com.letsblog.media.client.GenerationJobClient;
import com.letsblog.media.client.GenerationJobSummary;
import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.dto.AiImageBatchResponse;
import com.letsblog.media.dto.AiImageRequest;
import com.letsblog.media.dto.CreateGeneratedImageRequest;
import com.letsblog.media.dto.ImageGenerationOptionsResponse;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 画像生成のオーケストレーション(issue #1101)。
 *
 * <p>#1101までseedの実値は{@code ComfyUiClient}の内部で決まっており、外へ出なかった。
 * このテストは決定が呼び出し側へ移ったこと — すなわち
 *
 * <ul>
 *   <li>COMFYUIへ渡す{@code ComfyUiGenerationParams.seed()}が常に非nullであること</li>
 *   <li>その実値が保存要求とAPIレスポンスの両方に載ること</li>
 *   <li>バッチ内の位置{@code batchIndex}が0起点で振られること</li>
 *   <li>seedを持たないCHATGPTではseedがNULLのままであること</li>
 * </ul>
 *
 * を固定する。
 *
 * <p><b>受け入れテスト(Gherkin)ではなくサービスレベルで表現している理由</b>:
 * seedの一致検証にはCOMFYUIプロバイダでの実生成が要る。受け入れテスト環境の
 * ComfyUIはGPU必須の任意サービスで、{@code infra/e2e-stubs/}にスタブも無く、
 * OpenAI画像スタブはseedの概念を持たない。CLAUDE.md → Test-First Implementation が
 * 認める「文書化された例外」として、ここで表現する(#1106でATから到達可能になる)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("media-service: 画像生成のseedとバッチ内位置(issue #1101)")
class ImageGenerationServiceTest {

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
                prohibitedContentFilterService, new SeedResolver(), request);

        when(imageModelService.getSelectedProvider(any())).thenReturn(ImageProvider.COMFYUI);
        when(comfyUiModelService.getSelectedCheckpointOrGlobalDefault(any())).thenReturn("global.safetensors");
        when(defaultsResolver.resolveDefaultNegativePrompt(any())).thenReturn("default negative");
        when(defaultsResolver.resolveDefaultQualityPrompt(any())).thenReturn("masterpiece");
        when(defaultsResolver.resolveDefaultGeneratedImageWidth(any())).thenReturn(1024);
        when(defaultsResolver.resolveDefaultGeneratedImageHeight(any())).thenReturn(768);
        when(generationJobClient.create(anyString(), anyString(), any()))
                .thenReturn(new GenerationJobSummary(9L, "comfyui_image", "running", null, null));
        when(aiGenerationClient.generate(any(), anyString(), any())).thenReturn("{\"tags\":[\"猫\"]}");
        when(request.getHeader(anyString())).thenReturn("Bearer token");
        when(generatedImageCreationService.create(any())).thenAnswer(inv -> {
            GeneratedImage saved = new GeneratedImage();
            saved.setId(100L);
            return saved;
        });
    }

    private void comfyReturns(int count) {
        when(comfyUiClient.generateImage(any())).thenAnswer(inv -> images(count));
    }

    private static List<ComfyUiImage> images(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new ComfyUiImage("img" + i + ".png", new byte[]{(byte) i}, "image/png"))
                .toList();
    }

    private ComfyUiGenerationParams capturedComfyParams() {
        ArgumentCaptor<ComfyUiGenerationParams> captor =
                ArgumentCaptor.forClass(ComfyUiGenerationParams.class);
        verify(comfyUiClient).generateImage(captor.capture());
        return captor.getValue();
    }

    private List<CreateGeneratedImageRequest> capturedSaveRequests(int times) {
        ArgumentCaptor<CreateGeneratedImageRequest> captor =
                ArgumentCaptor.forClass(CreateGeneratedImageRequest.class);
        verify(generatedImageCreationService, org.mockito.Mockito.times(times)).create(captor.capture());
        return captor.getAllValues();
    }

    private static AiImageRequest requestWithSeed(Long seed) {
        return new AiImageRequest(
                "a cat", null, null, null, null, null, seed, null, null, null, null, null, null, 1L);
    }

    // --- AC1 / AC2: seed未指定でも実値が返り、保存される ---

    @Test
    void seed未指定でも実値が決まりレスポンスと保存要求の両方に載る() {
        comfyReturns(1);

        AiImageBatchResponse response = service.generateImage(requestWithSeed(null));

        Long seed = response.images().get(0).seed();
        assertNotNull(seed, "seed未指定でもレスポンスのseedは非nullでなければならない");
        assertTrue(seed >= 0, "seedは0以上でなければならない: " + seed);
        assertEquals(seed, capturedComfyParams().seed(), "ComfyUIへ渡したseedと返したseedは同じ");
        assertEquals(seed, capturedSaveRequests(1).get(0).seed(), "保存したseedと返したseedは同じ");
    }

    @Test
    void COMFYUIへ渡すパラメータのseedは常に非nullになる() {
        comfyReturns(1);

        service.generateImage(requestWithSeed(null));

        assertNotNull(capturedComfyParams().seed());
    }

    // --- AC4: 明示指定したseedはそのまま ---

    @Test
    void 明示指定したseedはそのままレスポンスに返る() {
        comfyReturns(1);

        AiImageBatchResponse response = service.generateImage(requestWithSeed(555L));

        assertEquals(555L, response.images().get(0).seed());
        assertEquals(555L, capturedComfyParams().seed());
        assertEquals(555L, capturedSaveRequests(1).get(0).seed());
    }

    // --- AC5: バッチ内位置 ---

    @Test
    void batchSize2で生成するとbatchIndexが0と1になる() {
        comfyReturns(2);

        AiImageBatchResponse response = service.generateImage(new AiImageRequest(
                "a cat", null, null, null, null, null, 7L, null, null, 2, null, null, null, 1L));

        assertEquals(2, response.images().size());
        assertEquals(0, response.images().get(0).batchIndex());
        assertEquals(1, response.images().get(1).batchIndex());

        List<CreateGeneratedImageRequest> saved = capturedSaveRequests(2);
        assertEquals(0, saved.get(0).batchIndex());
        assertEquals(1, saved.get(1).batchIndex());
        assertEquals(7L, saved.get(0).seed());
        assertEquals(7L, saved.get(1).seed());
    }

    // --- AC6: CHATGPTはseedを持たない ---

    @Test
    void CHATGPTで生成した画像のseedはNULLのまま保存される() {
        when(imageModelService.getSelectedProvider(any())).thenReturn(ImageProvider.CHATGPT);
        when(chatGptImageClient.generateImage(any())).thenAnswer(inv -> images(1));

        AiImageBatchResponse response = service.generateImage(requestWithSeed(null));

        assertNull(response.images().get(0).seed());
        assertNull(capturedSaveRequests(1).get(0).seed());
        assertEquals("CHATGPT", capturedSaveRequests(1).get(0).provider());
        verify(comfyUiClient, never()).generateImage(any());
        verify(generationJobClient).create(eq("chatgpt_image"), anyString(), any());
    }

    @Test
    void CHATGPTではseedを明示しても保存はNULLになる() {
        when(imageModelService.getSelectedProvider(any())).thenReturn(ImageProvider.CHATGPT);
        when(chatGptImageClient.generateImage(any())).thenAnswer(inv -> images(1));

        AiImageBatchResponse response = service.generateImage(requestWithSeed(999L));

        assertNull(response.images().get(0).seed());
        assertNull(capturedSaveRequests(1).get(0).seed());
    }

    // --- AC7: 既存挙動の回帰 ---

    @Test
    void 未指定のパラメータはプロジェクト既定値とサーバー既定値で補完する() {
        comfyReturns(1);

        service.generateImage(requestWithSeed(1L));

        ComfyUiGenerationParams params = capturedComfyParams();
        assertEquals("a cat, masterpiece", params.prompt());
        assertEquals("default negative", params.negativePrompt());
        assertEquals(20, params.steps());
        assertEquals(7.0, params.cfgScale());
        assertEquals("euler", params.samplerName());
        assertEquals("normal", params.scheduler());
        assertEquals(1024, params.width());
        assertEquals(768, params.height());
        assertEquals(1, params.batchSize());
        assertEquals("global.safetensors", params.checkpoint());
    }

    @Test
    void 明示指定したパラメータは既定値で上書きされない() {
        comfyReturns(1);

        service.generateImage(new AiImageRequest(
                "a dog", "ugly", 30, 9.5, "dpmpp_2m", "karras", 3L, 640, 640, 2,
                "mine.safetensors", "lora.safetensors", 0.4, 1L));

        ComfyUiGenerationParams params = capturedComfyParams();
        assertEquals("a dog, masterpiece", params.prompt());
        assertEquals("ugly", params.negativePrompt());
        assertEquals(30, params.steps());
        assertEquals(9.5, params.cfgScale());
        assertEquals("dpmpp_2m", params.samplerName());
        assertEquals("karras", params.scheduler());
        assertEquals(640, params.width());
        assertEquals(640, params.height());
        assertEquals(2, params.batchSize());
        assertEquals("mine.safetensors", params.checkpoint());
        assertEquals("lora.safetensors", params.loraName());
        assertEquals(0.4, params.loraWeight());
    }

    @Test
    void 空白のcheckpointとnegativePromptは既定値へフォールバックする() {
        comfyReturns(1);

        service.generateImage(new AiImageRequest(
                "a cat", "   ", null, null, null, null, 1L, null, null, null, "  ", null, null, 1L));

        ComfyUiGenerationParams params = capturedComfyParams();
        assertEquals("global.safetensors", params.checkpoint());
        assertEquals("default negative", params.negativePrompt());
    }

    @Test
    void 品質プロンプトが空ならプロンプトへ何も足さない() {
        comfyReturns(1);
        when(defaultsResolver.resolveDefaultQualityPrompt(any())).thenReturn("   ");

        service.generateImage(requestWithSeed(1L));

        assertEquals("a cat", capturedComfyParams().prompt());
    }

    @Test
    void 品質プロンプトがnullならプロンプトへ何も足さない() {
        comfyReturns(1);
        when(defaultsResolver.resolveDefaultQualityPrompt(any())).thenReturn(null);

        service.generateImage(requestWithSeed(1L));

        assertEquals("a cat", capturedComfyParams().prompt());
    }

    @Test
    void 禁止コンテンツの検査は解決済みプロンプトに対して行う() {
        comfyReturns(1);
        when(defaultsResolver.resolveBlockSexualContent(1L)).thenReturn(true);
        when(defaultsResolver.resolveBlockViolentContent(1L)).thenReturn(false);
        when(defaultsResolver.resolveBlockDiscriminatoryContent(1L)).thenReturn(true);

        service.generateImage(requestWithSeed(1L));

        verify(prohibitedContentFilterService).check("a cat, masterpiece", true, false, true);
    }

    @Test
    void 生成に失敗したらジョブを失敗として記録し例外を投げ直す() {
        when(comfyUiClient.generateImage(any())).thenThrow(new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class, () -> service.generateImage(requestWithSeed(1L)));

        verify(generationJobClient).updateStatus(eq(9L), eq("failed"), anyString(), any());
    }

    @Test
    void 生成に成功したらジョブを完了として記録する() {
        comfyReturns(2);

        service.generateImage(new AiImageRequest(
                "a cat", null, null, null, null, null, 1L, null, null, 2, null, null, null, 1L));

        verify(generationJobClient).updateStatus(eq(9L), eq("done"), eq("{\"count\":\"2\"}"), any());
    }

    // --- タグ提案(issue #281 の回帰) ---

    @Test
    void タグ提案のJSONを保存要求へ渡す() {
        comfyReturns(1);

        service.generateImage(requestWithSeed(1L));

        assertEquals("[\"猫\"]", capturedSaveRequests(1).get(0).tagsJson());
    }

    @Test
    void タグ提案が前後に文章を含んでもJSON部分だけを取り出す() {
        comfyReturns(1);
        when(aiGenerationClient.generate(any(), anyString(), any()))
                .thenReturn("はい、こちらです。{\"tags\":[\"犬\",\"散歩\"]} 以上です。");

        service.generateImage(requestWithSeed(1L));

        assertEquals("[\"犬\",\"散歩\"]", capturedSaveRequests(1).get(0).tagsJson());
    }

    @Test
    void タグ提案がJSONでなければタグなしで保存する() {
        comfyReturns(1);
        when(aiGenerationClient.generate(any(), anyString(), any())).thenReturn("すみません、わかりません");

        service.generateImage(requestWithSeed(1L));

        assertNull(capturedSaveRequests(1).get(0).tagsJson());
    }

    @Test
    void タグ提案の閉じ括弧が無ければタグなしで保存する() {
        comfyReturns(1);
        when(aiGenerationClient.generate(any(), anyString(), any())).thenReturn("{\"tags\":[\"猫\"]");

        service.generateImage(requestWithSeed(1L));

        assertNull(capturedSaveRequests(1).get(0).tagsJson());
    }

    @Test
    void タグ提案の括弧の順序が逆ならタグなしで保存する() {
        comfyReturns(1);
        when(aiGenerationClient.generate(any(), anyString(), any())).thenReturn("} これは壊れています {");

        service.generateImage(requestWithSeed(1L));

        assertNull(capturedSaveRequests(1).get(0).tagsJson());
    }

    @Test
    void タグ提案が空配列ならタグなしで保存する() {
        comfyReturns(1);
        when(aiGenerationClient.generate(any(), anyString(), any())).thenReturn("{\"tags\":[]}");

        service.generateImage(requestWithSeed(1L));

        assertNull(capturedSaveRequests(1).get(0).tagsJson());
    }

    @Test
    void タグ提案の応答にtagsが無ければタグなしで保存する() {
        comfyReturns(1);
        when(aiGenerationClient.generate(any(), anyString(), any())).thenReturn("{\"other\":1}");

        service.generateImage(requestWithSeed(1L));

        assertNull(capturedSaveRequests(1).get(0).tagsJson());
    }

    @Test
    void タグ提案のtagsが配列でなければタグなしで保存する() {
        comfyReturns(1);
        when(aiGenerationClient.generate(any(), anyString(), any())).thenReturn("{\"tags\":\"猫\"}");

        service.generateImage(requestWithSeed(1L));

        assertNull(capturedSaveRequests(1).get(0).tagsJson());
    }

    @Test
    void タグ提案のLLM呼び出しが失敗しても画像生成は成功する() {
        comfyReturns(1);
        when(aiGenerationClient.generate(any(), anyString(), any()))
                .thenThrow(new IllegalStateException("LLM down"));

        AiImageBatchResponse response = service.generateImage(requestWithSeed(1L));

        assertEquals(1, response.images().size());
        assertNull(capturedSaveRequests(1).get(0).tagsJson());
    }

    // --- 画像生成オプション ---

    @Test
    void 画像生成オプションを組み立てて返す() {
        when(comfyUiClient.listCheckpoints()).thenReturn(List.of("a.safetensors"));
        when(comfyUiClient.listSamplers()).thenReturn(List.of("euler"));
        when(comfyUiClient.listSchedulers()).thenReturn(List.of("normal"));
        when(comfyUiClient.listLoras()).thenReturn(List.of("anime.safetensors"));

        ImageGenerationOptionsResponse options = service.getImageOptions(1L);

        assertEquals(List.of("a.safetensors"), options.checkpoints());
        assertEquals("global.safetensors", options.selectedCheckpoint());
        assertEquals(List.of("euler"), options.samplers());
        assertEquals(List.of("normal"), options.schedulers());
        assertEquals(List.of("anime.safetensors"), options.loras());
        assertEquals(1024, options.defaultWidth());
        assertEquals(768, options.defaultHeight());
        assertEquals("default negative", options.defaultNegativePrompt());
        assertEquals("masterpiece", options.defaultQualityPrompt());
    }

    @Test
    void 画像はBase64で返す() {
        comfyReturns(1);

        AiImageBatchResponse response = service.generateImage(requestWithSeed(1L));

        assertEquals(java.util.Base64.getEncoder().encodeToString(new byte[]{0}),
                response.images().get(0).dataBase64());
        assertEquals("img0.png", response.images().get(0).fileName());
        assertEquals(100L, response.images().get(0).id());
        assertEquals("image/png", response.images().get(0).mimeType());
    }

    @Test
    void ジョブ記録のペイロードにはプロンプトを含める() {
        comfyReturns(1);

        service.generateImage(requestWithSeed(1L));

        verify(generationJobClient).create(eq("comfyui_image"), eq("{\"prompt\":\"a cat\"}"), eq("Bearer token"));
        verify(imageModelService).getSelectedProvider(anyLong());
    }
}
