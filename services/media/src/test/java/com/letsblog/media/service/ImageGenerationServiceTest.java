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
import com.letsblog.media.dto.AiImageResponse;
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
 * <p>issue #1102でここに「1リクエストでbatchCount回繰り返す」ふるまいを足した。
 * リピートごとにseedを変え、途中のリピートが失敗しても成功分を返す。
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
                "a cat", null, null, null, null, null, seed, null, null, null, null, null, null, null, 1L);
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
                "a cat", null, null, null, null, null, 7L, null, null, 2, null, null, null, null, 1L));

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
                "a dog", "ugly", 30, 9.5, "dpmpp_2m", "karras", 3L, 640, 640, 2, null,
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
                "a cat", "   ", null, null, null, null, 1L, null, null, null, null, "  ", null, null, 1L));

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

    /**
     * issue #1102で結果ペイロードの形を変えた。従来は{@code {"count":"2"}}だけだったが、
     * 部分失敗(一部のリピートだけ失敗)を成功として返すようになったため、
     * <b>何回成功して何回失敗したか</b>が履歴に残らないと、後から
     * 「256枚頼んで200枚しか無い」理由を追えない(Requirements 10)。
     */
    @Test
    void 生成に成功したらジョブを完了として総枚数と成功失敗リピート数を記録する() {
        comfyReturns(2);

        service.generateImage(requestFromJson(
                "{\"prompt\":\"a cat\",\"seed\":1,\"batchSize\":2,\"projectId\":1}"));

        com.fasterxml.jackson.databind.JsonNode result = capturedJobResult("done");
        assertEquals("2", result.path("count").asText());
        assertEquals("1", result.path("succeededRepeats").asText());
        assertEquals("0", result.path("failedRepeats").asText());
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

    // ------------------------------------------------------------------
    // issue #1102: batch count(1リクエストで batchSize × batchCount 枚)
    // ------------------------------------------------------------------

    /**
     * リクエストをJSONから組み立てる。レコードのコンポーネント順に依存せず、
     * <b>APIが受け取るワイヤ表現そのもの</b>(batchCountというフィールドを受理するか)を
     * 同時に固定できる。
     */
    private AiImageRequest requestFromJson(String json) {
        try {
            return new ObjectMapper().readValue(json, AiImageRequest.class);
        } catch (Exception e) {
            throw new IllegalStateException("リクエストJSONを AiImageRequest へ解釈できません: " + json, e);
        }
    }

    /**
     * レスポンスの失敗リピート数。レコードのコンポーネントではなく<b>JSON表現</b>から読むのは、
     * 呼び出し側(Web/拡張)が実際に見るのがJSONだからであり、フィールドが存在しないことを
     * 明確な失敗として報告できるようにするため。
     */
    private static int failedRepeats(AiImageBatchResponse response) {
        com.fasterxml.jackson.databind.JsonNode node =
                new ObjectMapper().valueToTree(response).get("failedRepeats");
        assertNotNull(node, "レスポンスに failedRepeats がありません(失敗したリピート数を示せていない)");
        return node.asInt();
    }

    /** 各リピートでComfyUIへ渡されたパラメータを、呼ばれた順に返す。 */
    private List<ComfyUiGenerationParams> capturedComfyParams(int times) {
        ArgumentCaptor<ComfyUiGenerationParams> captor =
                ArgumentCaptor.forClass(ComfyUiGenerationParams.class);
        verify(comfyUiClient, org.mockito.Mockito.times(times)).generateImage(captor.capture());
        return captor.getAllValues();
    }

    /** generation_jobs へ記録した結果ペイロード(JSON)。 */
    private com.fasterxml.jackson.databind.JsonNode capturedJobResult(String status) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).updateStatus(eq(9L), eq(status), captor.capture(), any());
        try {
            return new ObjectMapper().readTree(captor.getValue());
        } catch (Exception e) {
            throw new IllegalStateException("ジョブ結果ペイロードがJSONではありません: " + captor.getValue(), e);
        }
    }

    // --- AC1: batchSize × batchCount 枚が1リクエストで返る ---

    @Test
    void batchSize2かつbatchCount3なら6枚返り3リピート実行される() {
        comfyReturns(2);

        AiImageBatchResponse response = service.generateImage(
                requestFromJson("{\"prompt\":\"a cat\",\"batchSize\":2,\"batchCount\":3,\"projectId\":1}"));

        assertEquals(6, response.images().size(), "batchSize × batchCount 枚が返らなければならない");
        assertEquals(3, capturedComfyParams(3).size(), "プロバイダ呼び出しはリピート回数ぶん");
        assertEquals(6, capturedSaveRequests(6).size(), "生成した全画像が1行ずつ保存される");
        assertEquals(0, failedRepeats(response));
    }

    // --- AC2: 同一リピート内は同じseed・異なるbatchIndex、リピート間はseedが違う ---

    @Test
    void 同一リピート内は同じseedで異なるbatchIndexになりリピート間はseedが異なる() {
        comfyReturns(2);

        AiImageBatchResponse response = service.generateImage(
                requestFromJson("{\"prompt\":\"a cat\",\"batchSize\":2,\"batchCount\":3,\"projectId\":1}"));

        List<AiImageResponse> images = response.images();
        for (int repeat = 0; repeat < 3; repeat++) {
            AiImageResponse first = images.get(repeat * 2);
            AiImageResponse second = images.get(repeat * 2 + 1);
            assertEquals(first.seed(), second.seed(), "同一リピート内の2枚は同じseed");
            assertEquals(0, first.batchIndex(), "batchIndexはリピートごとに0から振り直す");
            assertEquals(1, second.batchIndex());
        }
        long distinctSeeds = images.stream().map(AiImageResponse::seed).distinct().count();
        assertEquals(3, distinctSeeds, "リピートごとにseedが変わらなければならない");
    }

    // --- AC3: seed指定時は seed + repeatIndex ---

    @Test
    void seed指定時はリピートごとにseedが1ずつ増える() {
        comfyReturns(1);

        AiImageBatchResponse response = service.generateImage(requestFromJson(
                "{\"prompt\":\"a cat\",\"seed\":12345,\"batchCount\":3,\"projectId\":1}"));

        List<ComfyUiGenerationParams> params = capturedComfyParams(3);
        assertEquals(12345L, params.get(0).seed());
        assertEquals(12346L, params.get(1).seed());
        assertEquals(12347L, params.get(2).seed());
        assertEquals(List.of(12345L, 12346L, 12347L),
                response.images().stream().map(AiImageResponse::seed).toList());
    }

    // --- AC4: seed未指定時はリピートごとに新しいランダムseed ---

    @Test
    void seed未指定時はリピートごとに異なるランダムseedを引く() {
        comfyReturns(1);

        AiImageBatchResponse response = service.generateImage(
                requestFromJson("{\"prompt\":\"a cat\",\"batchCount\":3,\"projectId\":1}"));

        List<Long> seeds = response.images().stream().map(AiImageResponse::seed).toList();
        assertEquals(3, seeds.stream().distinct().count(), "3リピートのseedは互いに異なる: " + seeds);
        seeds.forEach(seed -> assertTrue(seed != null && seed >= 0, "seedは0以上の実値: " + seed));
    }

    // --- AC5: batchCount省略時の後方互換 ---

    @Test
    void batchCount省略時は従来どおり1リピートだけ実行する() {
        comfyReturns(2);

        AiImageBatchResponse response = service.generateImage(
                requestFromJson("{\"prompt\":\"a cat\",\"batchSize\":2,\"projectId\":1}"));

        assertEquals(2, response.images().size());
        assertEquals(1, capturedComfyParams(1).size());
    }

    // --- AC6: batchSize=16(COMFYUI) ---

    @Test
    void COMFYUIではbatchSize16を1リピートで生成する() {
        comfyReturns(16);

        AiImageBatchResponse response = service.generateImage(
                requestFromJson("{\"prompt\":\"a cat\",\"batchSize\":16,\"projectId\":1}"));

        assertEquals(16, response.images().size());
        assertEquals(16, capturedComfyParams(1).get(0).batchSize());
    }

    // --- AC10: CHATGPTはプロバイダ別上限(n ≤ 10) ---

    @Test
    void CHATGPTでbatchSizeが10を超えると生成を開始せず失敗する() {
        when(imageModelService.getSelectedProvider(any())).thenReturn(ImageProvider.CHATGPT);

        AiImageRequest imageRequest =
                requestFromJson("{\"prompt\":\"a cat\",\"batchSize\":11,\"projectId\":1}");

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.generateImage(imageRequest));

        assertTrue(e.getMessage().contains("CHATGPT"),
                "エラーメッセージにプロバイダ名を含める: " + e.getMessage());
        assertTrue(e.getMessage().contains("10"),
                "エラーメッセージに上限値を含める: " + e.getMessage());
        verify(chatGptImageClient, never()).generateImage(any());
        verify(generatedImageCreationService, never()).create(any());
        verify(generationJobClient, never()).create(anyString(), anyString(), any());
    }

    @Test
    void CHATGPTでもbatchSize10までは生成する() {
        when(imageModelService.getSelectedProvider(any())).thenReturn(ImageProvider.CHATGPT);
        when(chatGptImageClient.generateImage(any())).thenAnswer(inv -> images(10));

        AiImageBatchResponse response = service.generateImage(
                requestFromJson("{\"prompt\":\"a cat\",\"batchSize\":10,\"projectId\":1}"));

        assertEquals(10, response.images().size());
    }

    @Test
    void COMFYUIではbatchSize11でも上限に触れない() {
        comfyReturns(11);

        AiImageBatchResponse response = service.generateImage(
                requestFromJson("{\"prompt\":\"a cat\",\"batchSize\":11,\"projectId\":1}"));

        assertEquals(11, response.images().size());
    }

    // --- AC9: 合計上限は設けない ---

    @Test
    void batchSize16かつbatchCount16は拒否せず受理する() {
        comfyReturns(16);

        AiImageBatchResponse response = service.generateImage(requestFromJson(
                "{\"prompt\":\"a cat\",\"batchSize\":16,\"batchCount\":16,\"projectId\":1}"));

        assertEquals(256, response.images().size(), "合計上限は設けないため256枚まで受理する");
    }

    // --- AC14: 部分失敗は成功分を返す ---

    @Test
    void 途中のリピートが失敗しても成功分を返し失敗リピート数を示す() {
        java.util.concurrent.atomic.AtomicInteger call = new java.util.concurrent.atomic.AtomicInteger();
        when(comfyUiClient.generateImage(any())).thenAnswer(inv -> {
            if (call.incrementAndGet() == 2) {
                throw new IllegalStateException("2リピート目でVRAM不足");
            }
            return images(1);
        });

        AiImageBatchResponse response = service.generateImage(
                requestFromJson("{\"prompt\":\"a cat\",\"batchCount\":3,\"projectId\":1}"));

        assertEquals(2, response.images().size(), "成功したリピートの画像は捨てない");
        assertEquals(1, failedRepeats(response), "失敗したリピート数を結果に示す");
        assertEquals(2, capturedSaveRequests(2).size());
    }

    @Test
    void 部分失敗でもジョブは完了として成功数と失敗数を記録する() {
        java.util.concurrent.atomic.AtomicInteger call = new java.util.concurrent.atomic.AtomicInteger();
        when(comfyUiClient.generateImage(any())).thenAnswer(inv -> {
            if (call.incrementAndGet() == 2) {
                throw new IllegalStateException("2リピート目でVRAM不足");
            }
            return images(2);
        });

        service.generateImage(requestFromJson(
                "{\"prompt\":\"a cat\",\"batchSize\":2,\"batchCount\":3,\"projectId\":1}"));

        com.fasterxml.jackson.databind.JsonNode result = capturedJobResult("done");
        assertEquals("4", result.path("count").asText(), "countは総枚数");
        assertEquals("2", result.path("succeededRepeats").asText());
        assertEquals("1", result.path("failedRepeats").asText());
    }

    @Test
    void 全リピートが失敗したらジョブを失敗として記録し例外を投げ直す() {
        when(comfyUiClient.generateImage(any())).thenThrow(new IllegalStateException("boom"));

        AiImageRequest imageRequest =
                requestFromJson("{\"prompt\":\"a cat\",\"batchCount\":3,\"projectId\":1}");

        assertThrows(IllegalStateException.class, () -> service.generateImage(imageRequest));

        verify(generationJobClient).updateStatus(eq(9L), eq("failed"), anyString(), any());
        verify(generatedImageCreationService, never()).create(any());
    }

    // --- レビュー指摘(IMPORTANT): 決定的な失敗をbatchCount回リトライしない ---

    /**
     * 認証エラー・設定不備・チェックポイント不在のように<b>毎回同じように失敗する原因</b>で、
     * 残り全てのリピートを試し続けてはならない(issue #1102 レビュー指摘)。
     *
     * <p>{@code /prompt}投入後に失敗する経路では1リピートごとにフルのポーリング予算
     * (batchSize=16で188秒)を使い切るため、打ち切りが無いと一つの設定ミスが
     * リクエストを最大50分掴んだあげく、1リピート目で既に判明していたエラーを返す。
     */
    @Test
    void 同じ原因で連続して失敗したら残りのリピートを打ち切る() {
        when(comfyUiClient.generateImage(any()))
                .thenThrow(new IllegalStateException("checkpoint not found: x.safetensors"));

        AiImageRequest imageRequest =
                requestFromJson("{\"prompt\":\"a cat\",\"batchCount\":16,\"projectId\":1}");

        assertThrows(IllegalStateException.class, () -> service.generateImage(imageRequest));

        verify(comfyUiClient, org.mockito.Mockito.times(2)).generateImage(any());
    }

    /**
     * 成功したあとに連続失敗した場合も、それまでの成功分は捨てない(Requirement 10)。
     * 打ち切ったぶんのリピートは<b>失敗として数える</b>ので、
     * 成功リピート数 + 失敗リピート数 は常に要求したリピート回数と一致する。
     */
    @Test
    void 打ち切っても成功分は返し打ち切ったリピートも失敗として数える() {
        java.util.concurrent.atomic.AtomicInteger call = new java.util.concurrent.atomic.AtomicInteger();
        when(comfyUiClient.generateImage(any())).thenAnswer(inv -> {
            if (call.incrementAndGet() >= 2) {
                throw new IllegalStateException("ComfyUIの認証に失敗しました");
            }
            return images(1);
        });

        AiImageBatchResponse response = service.generateImage(
                requestFromJson("{\"prompt\":\"a cat\",\"batchCount\":8,\"projectId\":1}"));

        assertEquals(1, response.images().size(), "成功した1リピート分の画像は返す");
        assertEquals(7, failedRepeats(response),
                "打ち切った5回を含め、成功しなかった7リピートを失敗として数える");
        verify(comfyUiClient, org.mockito.Mockito.times(3)).generateImage(any());
    }

    /** 打ち切ったことと、実際に何回試したのかがジョブ履歴から追えること。 */
    @Test
    void 打ち切りはジョブ履歴に試行回数と打ち切りの事実として残る() {
        java.util.concurrent.atomic.AtomicInteger call = new java.util.concurrent.atomic.AtomicInteger();
        when(comfyUiClient.generateImage(any())).thenAnswer(inv -> {
            if (call.incrementAndGet() >= 2) {
                throw new IllegalStateException("ComfyUIの認証に失敗しました");
            }
            return images(1);
        });

        service.generateImage(
                requestFromJson("{\"prompt\":\"a cat\",\"batchCount\":8,\"projectId\":1}"));

        com.fasterxml.jackson.databind.JsonNode result = capturedJobResult("done");
        assertEquals("1", result.path("succeededRepeats").asText());
        assertEquals("7", result.path("failedRepeats").asText());
        assertEquals("3", result.path("attemptedRepeats").asText(),
                "実際にプロバイダを呼んだ回数(打ち切りで16回まで回していないこと)");
        assertEquals("true", result.path("aborted").asText(),
                "打ち切ったことが履歴から分かる");
    }

    /** 単発の失敗は打ち切りにしない(連続でなければ一過性の可能性がある)。 */
    @Test
    void 失敗が連続しなければ打ち切らない() {
        java.util.concurrent.atomic.AtomicInteger call = new java.util.concurrent.atomic.AtomicInteger();
        when(comfyUiClient.generateImage(any())).thenAnswer(inv -> {
            if (call.incrementAndGet() % 2 == 0) {
                throw new IllegalStateException("一過性のVRAM不足");
            }
            return images(1);
        });

        AiImageBatchResponse response = service.generateImage(
                requestFromJson("{\"prompt\":\"a cat\",\"batchCount\":6,\"projectId\":1}"));

        assertEquals(3, response.images().size(), "奇数回目は成功しているので3枚返る");
        assertEquals(3, failedRepeats(response));
        verify(comfyUiClient, org.mockito.Mockito.times(6)).generateImage(any());
        com.fasterxml.jackson.databind.JsonNode result = capturedJobResult("done");
        assertEquals("false", result.path("aborted").asText(), "打ち切っていない");
    }

    /**
     * 全リピートが失敗したときに投げるのは<b>最初の</b>失敗にする(issue #1102 レビュー指摘)。
     * 最後の失敗を投げ直す設計は、原因を作った最初の文脈を捨ててしまう。
     * 打ち切りは「最初の失敗と同じ原因が続いた」ことを根拠に行うので、
     * 呼び出し元が最も知りたいのは最初の失敗である。
     */
    @Test
    void 全リピートが失敗したら最初の失敗を投げる() {
        java.util.concurrent.atomic.AtomicInteger call = new java.util.concurrent.atomic.AtomicInteger();
        when(comfyUiClient.generateImage(any())).thenAnswer(inv -> {
            throw new IllegalStateException("失敗" + call.incrementAndGet());
        });

        AiImageRequest imageRequest =
                requestFromJson("{\"prompt\":\"a cat\",\"batchCount\":4,\"projectId\":1}");

        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> service.generateImage(imageRequest));

        assertEquals("失敗1", e.getMessage(), "最初の失敗を投げる(最後ではない)");
    }

    /**
     * 原因が異なる失敗が続いた場合、最初の失敗だけを返すと残りの文脈が消える。
     * 2件目以降の異なる失敗は{@code addSuppressed}で添えて、ログに全て残るようにする。
     */
    @Test
    void 異なる原因の失敗は最初の例外に添えて失われないようにする() {
        java.util.concurrent.atomic.AtomicInteger call = new java.util.concurrent.atomic.AtomicInteger();
        when(comfyUiClient.generateImage(any())).thenAnswer(inv -> {
            throw new IllegalStateException("失敗" + call.incrementAndGet());
        });

        AiImageRequest imageRequest =
                requestFromJson("{\"prompt\":\"a cat\",\"batchCount\":4,\"projectId\":1}");

        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> service.generateImage(imageRequest));

        assertEquals(1, e.getSuppressed().length, "2件目の失敗が添えられている");
        assertEquals("失敗2", e.getSuppressed()[0].getMessage());
    }

    // --- レビュー指摘(SUGGESTION): リピート間で変わらない既定値は1回だけ引く ---

    /**
     * プロジェクト既定値の解決はDB往復を伴う({@code ProjectImageDefaultsResolver}・
     * {@code ComfyUiModelService})。リピート間で変わらない値なので、
     * {@code batchCount}回引き直す必要は無い(issue #1102 レビュー指摘)。
     *
     * <p>品質プロンプトは元の実装で最も回数が多く、{@code batchCount=16}のとき
     * 18回(禁止コンテンツ検査1 + タグ提案1 + リピート16)引いていた。
     */
    @Test
    void リピート間で変わらない既定値はリピート回数によらず1回だけ引く() {
        comfyReturns(1);

        service.generateImage(
                requestFromJson("{\"prompt\":\"a cat\",\"batchCount\":16,\"projectId\":1}"));

        verify(defaultsResolver, org.mockito.Mockito.times(1)).resolveDefaultQualityPrompt(any());
        verify(defaultsResolver, org.mockito.Mockito.times(1)).resolveDefaultNegativePrompt(any());
        verify(defaultsResolver, org.mockito.Mockito.times(1)).resolveDefaultGeneratedImageWidth(any());
        verify(defaultsResolver, org.mockito.Mockito.times(1)).resolveDefaultGeneratedImageHeight(any());
        verify(comfyUiModelService, org.mockito.Mockito.times(1))
                .getSelectedCheckpointOrGlobalDefault(any());
    }

    /** 1回だけ引くようにしても、リピートごとに変わるseed以外は全リピートで同じ値になる。 */
    @Test
    void 既定値を1回だけ引いても全リピートに同じ値が渡る() {
        comfyReturns(1);

        service.generateImage(
                requestFromJson("{\"prompt\":\"a cat\",\"seed\":100,\"batchCount\":3,\"projectId\":1}"));

        List<ComfyUiGenerationParams> params = capturedComfyParams(3);
        for (ComfyUiGenerationParams p : params) {
            assertEquals("a cat, masterpiece", p.prompt());
            assertEquals("default negative", p.negativePrompt());
            assertEquals(1024, p.width());
            assertEquals(768, p.height());
            assertEquals("global.safetensors", p.checkpoint());
        }
        assertEquals(100L, params.get(0).seed());
        assertEquals(101L, params.get(1).seed());
        assertEquals(102L, params.get(2).seed());
    }

    // --- Implementation Notes: タグ提案はリピートをまたいで使い回す ---

    @Test
    void タグ提案はリピート回数によらず1回だけ呼ぶ() {
        comfyReturns(1);

        service.generateImage(
                requestFromJson("{\"prompt\":\"a cat\",\"batchCount\":3,\"projectId\":1}"));

        verify(aiGenerationClient, org.mockito.Mockito.times(1)).generate(any(), anyString(), any());
    }
}
