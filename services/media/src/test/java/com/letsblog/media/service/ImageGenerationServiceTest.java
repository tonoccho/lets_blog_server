package com.letsblog.media.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.media.ai.AiServiceException;
import com.letsblog.media.ai.SeedResolver;
import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.dto.AiImageBatchResponse;
import com.letsblog.media.dto.AiImageRequest;
import com.letsblog.media.dto.AiImageResponse;
import com.letsblog.media.dto.ImageGenerationOptionsResponse;
import com.letsblog.media.testsupport.BrokenObjectMapper;
import com.letsblog.media.testsupport.FakeHttpServer;
import com.letsblog.media.testsupport.MediaWorld;
import com.letsblog.media.testsupport.MediaWorld.ComfyParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 画像生成のオーケストレーション(issue #1101)。
 *
 * <p>#1101までseedの実値は{@code ComfyUiClient}の内部で決まっており、外へ出なかった。
 * このテストは決定が呼び出し側へ移ったこと — すなわち
 *
 * <ul>
 *   <li>COMFYUIへ渡す生成パラメータのseedが常に非nullであること</li>
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
 *
 * <p>issue #1819: {@code ImageGenerationService}とその協力者({@code ComfyUiClient}・{@code ChatGptImageClient}・
 * {@code AiGenerationClient}・{@code GenerationJobClient}・{@code ImageModelService}・{@code ComfyUiModelService}・
 * {@code ProjectImageDefaultsResolver}・{@code ProhibitedContentFilterService}・{@code GeneratedImageCreationService}など)は
 * すべて実物。ComfyUI・ChatGPT画像API・ai-service・platform-service・project-serviceをフェイクHTTPサーバー、
 * {@code generated_images}・{@code project_image_settings}をリポジトリのmock(メモリ上の行)が演じ、ファイルは
 * 一時ディレクトリへ実際に書く。ComfyUIへ渡ったパラメータは、投入されたワークフロー(JSON)から読み取る。
 */
@DisplayName("media-service: 画像生成のseedとバッチ内位置(issue #1101)")
class ImageGenerationServiceTest {

    private final MediaWorld world = new MediaWorld();
    private final ImageGenerationService service = world.imageGenerationService;

    @BeforeEach
    void setUp() {
        world.request.addHeader("Authorization", "Bearer token");
        // プロジェクト1の既定値(未指定の項目はここから補完される)。
        var settings = world.settingsFor(1L);
        settings.setDefaultNegativePrompt("default negative");
        settings.setDefaultQualityPrompt("masterpiece");
        settings.setDefaultGeneratedImageWidth(1024);
        settings.setDefaultGeneratedImageHeight(768);
    }

    @AfterEach
    void close() {
        world.close();
    }

    private ComfyParams comfy() {
        assertEquals(1, world.comfyWorkflows().size(), "ComfyUIへの投入は1回");
        return world.lastComfyParams();
    }

    private GeneratedImage saved(int index) {
        return world.savedImages.get(index);
    }

    /** プロジェクトを指定しない要求(品質プロンプトにサーバー既定値だけが使われる)。 */
    private static AiImageRequest withoutProject() {
        return new AiImageRequest(
                "a cat", null, null, null, null, null, 1L, null, null, null, null, null, null, null, null);
    }

    private static AiImageRequest requestWithSeed(Long seed) {
        return new AiImageRequest(
                "a cat", null, null, null, null, null, seed, null, null, null, null, null, null, null, 1L);
    }

    // --- issue #1503: ComfyUI接続先のプロジェクト単位上書きのため、生成にprojectIdが使われる ---

    @Test
    void ComfyUIの接続先はリクエストのprojectIdで引く() {
        service.generateImage(requestWithSeed(1L));

        assertEquals(1, world.http.requests("GET", "/api/internal/ai/projects/1/connections").size());
    }

    @Test
    void 画像生成オプションの一覧取得はプロジェクトの接続先を引く() {
        world.comfyObjectInfo(List.of("p42.safetensors"), List.of("euler"), List.of("normal"), List.of());

        ImageGenerationOptionsResponse options = service.getImageOptions(42L);

        assertEquals(List.of("p42.safetensors"), options.checkpoints());
        assertTrue(world.http.requests("GET", "/api/internal/ai/projects/42/connections").size() >= 1);
    }

    // --- AC1 / AC2: seed未指定でも実値が返り、保存される ---

    @Test
    void seed未指定でも実値が決まりレスポンスと保存要求の両方に載る() {
        world.comfyReturns(1);

        AiImageBatchResponse response = service.generateImage(requestWithSeed(null));

        Long seed = response.images().get(0).seed();
        assertNotNull(seed, "seed未指定でもレスポンスのseedは非nullでなければならない");
        assertTrue(seed >= 0, "seedは0以上でなければならない: " + seed);
        assertEquals(seed, comfy().seed(), "ComfyUIへ渡したseedと返したseedは同じ");
        assertEquals(seed, saved(0).getSeed(), "保存したseedと返したseedは同じ");
    }

    @Test
    void COMFYUIへ渡すパラメータのseedは常に非nullになる() {
        service.generateImage(requestWithSeed(null));

        JsonNode seed = world.lastComfyWorkflow().path("3").path("inputs").path("seed");
        assertTrue(seed.isIntegralNumber(), "ComfyUIへ投入するワークフローのseedは整数の実値: " + seed);
    }

    // --- AC4: 明示指定したseedはそのまま ---

    @Test
    void 明示指定したseedはそのままレスポンスに返る() {
        AiImageBatchResponse response = service.generateImage(requestWithSeed(555L));

        assertEquals(555L, response.images().get(0).seed());
        assertEquals(555L, comfy().seed());
        assertEquals(555L, saved(0).getSeed());
    }

    // --- AC5: バッチ内位置 ---

    @Test
    void batchSize2で生成するとbatchIndexが0と1になる() {
        world.comfyReturns(2);

        AiImageBatchResponse response = service.generateImage(new AiImageRequest(
                "a cat", null, null, null, null, null, 7L, null, null, 2, null, null, null, null, 1L));

        assertEquals(2, response.images().size());
        assertEquals(0, response.images().get(0).batchIndex());
        assertEquals(1, response.images().get(1).batchIndex());

        assertEquals(2, world.savedImages.size());
        assertEquals(0, saved(0).getBatchIndex());
        assertEquals(1, saved(1).getBatchIndex());
        assertEquals(7L, saved(0).getSeed());
        assertEquals(7L, saved(1).getSeed());
    }

    // --- AC6: CHATGPTはseedを持たない ---

    @Test
    void CHATGPTで生成した画像のseedはNULLのまま保存される() {
        world.providerIs(1L, "CHATGPT");

        AiImageBatchResponse response = service.generateImage(requestWithSeed(null));

        assertNull(response.images().get(0).seed());
        assertNull(saved(0).getSeed());
        assertEquals("CHATGPT", saved(0).getProvider());
        assertTrue(world.comfyWorkflows().isEmpty());
        assertEquals("chatgpt_image", world.jobCreates().get(0).type());
    }

    @Test
    void CHATGPTではseedを明示しても保存はNULLになる() {
        world.providerIs(1L, "CHATGPT");

        AiImageBatchResponse response = service.generateImage(requestWithSeed(999L));

        assertNull(response.images().get(0).seed());
        assertNull(saved(0).getSeed());
    }

    @Test
    void CHATGPTの画像APIにはAPIキーとプロンプトと枚数が渡る() {
        world.providerIs(1L, "CHATGPT");

        service.generateImage(new AiImageRequest(
                "a cat", null, null, null, null, null, null, 1024, 1024, 3, null, null, null, null, 1L));

        var call = world.http.requests("POST", "/images/generations").get(0);
        assertEquals("Bearer sk-test", call.header("Authorization"));
        JsonNode body = world.bodyOf(call);
        assertEquals("a cat, masterpiece", body.get("prompt").asText());
        assertEquals(3, body.get("n").asInt());
        assertEquals("1024x1024", body.get("size").asText());
    }

    // --- AC7: 既存挙動の回帰 ---

    @Test
    void 未指定のパラメータはプロジェクト既定値とサーバー既定値で補完する() {
        service.generateImage(requestWithSeed(1L));

        ComfyParams params = comfy();
        assertEquals("a cat, masterpiece", params.prompt());
        assertEquals("default negative", params.negativePrompt());
        assertEquals(20, params.steps());
        assertEquals(7.0, params.cfg());
        assertEquals("euler", params.sampler());
        assertEquals("normal", params.scheduler());
        assertEquals(1024, params.width());
        assertEquals(768, params.height());
        assertEquals(1, params.batchSize());
        assertEquals("global.safetensors", params.checkpoint());
    }

    @Test
    void 明示指定したパラメータは既定値で上書きされない() {
        world.comfyReturns(2);

        service.generateImage(new AiImageRequest(
                "a dog", "ugly", 30, 9.5, "dpmpp_2m", "karras", 3L, 640, 640, 2, null,
                "mine.safetensors", "lora.safetensors", 0.4, 1L));

        ComfyParams params = comfy();
        assertEquals("a dog, masterpiece", params.prompt());
        assertEquals("ugly", params.negativePrompt());
        assertEquals(30, params.steps());
        assertEquals(9.5, params.cfg());
        assertEquals("dpmpp_2m", params.sampler());
        assertEquals("karras", params.scheduler());
        assertEquals(640, params.width());
        assertEquals(640, params.height());
        assertEquals(2, params.batchSize());
        assertEquals("mine.safetensors", params.checkpoint());
        assertEquals("lora.safetensors", params.lora());
        assertEquals(0.4, params.loraWeight());
    }

    @Test
    void 空白のcheckpointとnegativePromptは既定値へフォールバックする() {
        service.generateImage(new AiImageRequest(
                "a cat", "   ", null, null, null, null, 1L, null, null, null, null, "  ", null, null, 1L));

        ComfyParams params = comfy();
        assertEquals("global.safetensors", params.checkpoint());
        assertEquals("default negative", params.negativePrompt());
    }

    @Test
    void プロジェクト未指定の生成はサーバー既定値で補完しプロジェクトを引かない() {
        service.generateImage(new AiImageRequest(
                "a cat", null, null, null, null, null, 1L, null, null, null, null, null, null, null, null));

        ComfyParams params = comfy();
        assertEquals("a cat, " + MediaWorld.GLOBAL_QUALITY, params.prompt());
        assertEquals(MediaWorld.GLOBAL_NEGATIVE, params.negativePrompt());
        assertEquals(1024, params.width());
        assertEquals(768, params.height());
        assertEquals(MediaWorld.GLOBAL_CHECKPOINT, params.checkpoint());
        assertTrue(world.http.requests("GET", "/api/internal/project/.*").isEmpty());
    }

    @Test
    void 品質プロンプトが空ならプロンプトへ何も足さない() {
        try (MediaWorld blankQuality = new MediaWorld("   ")) {
            blankQuality.imageGenerationService.generateImage(withoutProject());
            assertEquals("a cat", blankQuality.lastComfyParams().prompt());
        }
    }

    @Test
    void 品質プロンプトがnullならプロンプトへ何も足さない() {
        try (MediaWorld nullQuality = new MediaWorld(null)) {
            nullQuality.imageGenerationService.generateImage(withoutProject());
            assertEquals("a cat", nullQuality.lastComfyParams().prompt());
        }
    }

    // ------------------------------------------------------------------
    // issue #1085: 安全側ネガティブプロンプトの連結
    // ------------------------------------------------------------------

    @Test
    void 性的ブロックがONならnegativePrompt未指定でも安全側の抑制語が連結される() {
        world.settingsFor(1L).setBlockSexualContent(true);

        service.generateImage(requestWithSeed(1L));

        assertEquals("default negative, nsfw, nude", comfy().negativePrompt());
    }

    @Test
    void 性的ブロックがONならユーザー指定のnegativePromptと抑制語の両方が残る() {
        world.settingsFor(1L).setBlockSexualContent(true);

        service.generateImage(new AiImageRequest(
                "a cat", "cat", null, null, null, null, 1L, null, null, null, null, null, null, null, 1L));

        assertEquals("cat, nsfw, nude", comfy().negativePrompt());
    }

    @Test
    void 性的ブロックがOFFなら安全側の抑制語は連結されない() {
        world.settingsFor(1L).setBlockSexualContent(false);

        service.generateImage(requestWithSeed(1L));

        assertEquals("default negative", comfy().negativePrompt());
    }

    @Test
    void 暴力的ブロックと差別的ブロックはカテゴリごとに独立して反映される() {
        world.settingsFor(1L).setBlockViolentContent(true);
        world.settingsFor(1L).setBlockDiscriminatoryContent(false);

        service.generateImage(requestWithSeed(1L));

        String negativePrompt = comfy().negativePrompt();
        assertTrue(negativePrompt.contains("gore"), negativePrompt);
        assertFalse(negativePrompt.contains("nazi symbol"), negativePrompt);
    }

    @Test
    void 差別的ブロックのみONなら差別側の抑制語だけが連結される() {
        world.settingsFor(1L).setBlockDiscriminatoryContent(true);

        service.generateImage(requestWithSeed(1L));

        String negativePrompt = comfy().negativePrompt();
        assertTrue(negativePrompt.contains("nazi symbol"), negativePrompt);
        assertFalse(negativePrompt.contains("gore"), negativePrompt);
    }

    @Test
    void ユーザー指定のnegativePromptに既に同じ抑制語が含まれていれば大小文字無視で重複させない() {
        world.settingsFor(1L).setBlockSexualContent(true);

        service.generateImage(new AiImageRequest(
                "a cat", "NSFW", null, null, null, null, 1L, null, null, null, null, null, null, null, 1L));

        assertEquals("NSFW, nude", comfy().negativePrompt());
    }

    @Test
    void 安全側ネガティブプロンプトの連結有無にかかわらずpositive側プロンプトは変わらない() {
        var settings = world.settingsFor(1L);
        settings.setBlockSexualContent(true);
        settings.setBlockViolentContent(true);
        settings.setBlockDiscriminatoryContent(true);

        service.generateImage(requestWithSeed(1L));

        assertEquals("a cat, masterpiece", comfy().prompt());
    }

    @Test
    void CHATGPT経路では性的ブロックがONでも安全側の抑制語を保存しない() {
        world.providerIs(1L, "CHATGPT");
        var settings = world.settingsFor(1L);
        settings.setBlockSexualContent(true);
        settings.setBlockViolentContent(true);
        settings.setBlockDiscriminatoryContent(true);

        service.generateImage(requestWithSeed(1L));

        assertEquals("default negative", saved(0).getNegativePrompt(),
                "ChatGPT経路では安全側ネガティブプロンプトの連結を行わない(#1085 Requirement 6)");
    }

    @Test
    void 禁止コンテンツの検査は解決済みプロンプトに対して行う() {
        // 利用者のプロンプトには禁止語が無く、プロジェクト既定の品質プロンプトにだけ含まれる。
        var settings = world.settingsFor(1L);
        settings.setDefaultQualityPrompt("nude");
        settings.setBlockSexualContent(true);
        settings.setBlockViolentContent(false);
        settings.setBlockDiscriminatoryContent(true);

        assertThrows(ProhibitedContentException.class, () -> service.generateImage(requestWithSeed(1L)));

        assertTrue(world.comfyWorkflows().isEmpty(), "ブロックされたら生成しない");
    }

    @Test
    void 禁止コンテンツの検査はONのカテゴリだけが対象になる() {
        var settings = world.settingsFor(1L);
        settings.setDefaultQualityPrompt("gore");
        settings.setBlockSexualContent(true);
        settings.setBlockViolentContent(false);
        settings.setBlockDiscriminatoryContent(true);

        service.generateImage(requestWithSeed(1L));

        assertEquals(1, world.comfyWorkflows().size(), "暴力のブロックがOFFなら通る");
    }

    @Test
    void 生成に失敗したらジョブを失敗として記録し例外を投げ直す() {
        world.http.status("POST", "/prompt", 500, "{\"error\":\"boom\"}");

        AiServiceException e = assertThrows(AiServiceException.class, () -> service.generateImage(requestWithSeed(1L)));

        assertTrue(e.getMessage().contains("boom"));
        List<MediaWorld.JobUpdate> failed = world.jobUpdates("failed");
        assertEquals(1, failed.size());
        assertEquals(9L, failed.get(0).jobId());
        assertTrue(failed.get(0).payload().get("error").asText().contains("boom"));
    }

    /**
     * issue #1102で結果ペイロードの形を変えた。従来は{@code {"count":"2"}}だけだったが、
     * 部分失敗(一部のリピートだけ失敗)を成功として返すようになったため、
     * <b>何回成功して何回失敗したか</b>が履歴に残らないと、後から
     * 「256枚頼んで200枚しか無い」理由を追えない(Requirements 10)。
     */
    @Test
    void 生成に成功したらジョブを完了として総枚数と成功失敗リピート数を記録する() {
        world.comfyReturns(2);

        service.generateImage(requestFromJson(
                "{\"prompt\":\"a cat\",\"seed\":1,\"batchSize\":2,\"projectId\":1}"));

        JsonNode result = jobResult("done");
        assertEquals("2", result.path("count").asText());
        assertEquals("1", result.path("succeededRepeats").asText());
        assertEquals("0", result.path("failedRepeats").asText());
    }

    @Test
    void ジョブの履歴をJSON化できなくても空のJSONで記録して生成は成功する() {
        ImageGenerationService broken = new ImageGenerationService(
                world.aiGenerationClient, world.comfyUiClient, world.chatGptImageClient, world.imageModelService,
                world.comfyUiModelService, world.creationService, world.generationJobClient,
                BrokenObjectMapper.create(), world.defaultsResolver, world.prohibitedContentFilterService,
                world.safetyNegativePromptService, new SeedResolver(), world.referenceImageService, world.request);

        AiImageBatchResponse response = broken.generateImage(requestWithSeed(1L));

        assertEquals(1, response.images().size());
        var create = world.bodyOf(world.http.requests("POST", "/api/internal/ai/generation-jobs").get(0));
        assertEquals("{}", create.get("requestPayload").asText());
        assertNull(saved(0).getTagsJson(), "タグのJSON化に失敗してもタグなしで保存を続ける");
    }

    // --- タグ提案(issue #281 の回帰) ---

    @Test
    void タグ提案のJSONを保存要求へ渡す() {
        service.generateImage(requestWithSeed(1L));

        assertEquals("[\"猫\"]", saved(0).getTagsJson());
    }

    @Test
    void タグ提案が前後に文章を含んでもJSON部分だけを取り出す() {
        world.llmReturns("はい、こちらです。{\"tags\":[\"犬\",\"散歩\"]} 以上です。");

        service.generateImage(requestWithSeed(1L));

        assertEquals("[\"犬\",\"散歩\"]", saved(0).getTagsJson());
    }

    @Test
    void タグ提案がJSONでなければタグなしで保存する() {
        world.llmReturns("すみません、わかりません");

        service.generateImage(requestWithSeed(1L));

        assertNull(saved(0).getTagsJson());
    }

    @Test
    void タグ提案の閉じ括弧が無ければタグなしで保存する() {
        world.llmReturns("{\"tags\":[\"猫\"]");

        service.generateImage(requestWithSeed(1L));

        assertNull(saved(0).getTagsJson());
    }

    @Test
    void タグ提案の括弧の順序が逆ならタグなしで保存する() {
        world.llmReturns("} これは壊れています {");

        service.generateImage(requestWithSeed(1L));

        assertNull(saved(0).getTagsJson());
    }

    @Test
    void タグ提案が空配列ならタグなしで保存する() {
        world.llmReturns("{\"tags\":[]}");

        service.generateImage(requestWithSeed(1L));

        assertNull(saved(0).getTagsJson());
    }

    @Test
    void タグ提案の応答にtagsが無ければタグなしで保存する() {
        world.llmReturns("{\"other\":1}");

        service.generateImage(requestWithSeed(1L));

        assertNull(saved(0).getTagsJson());
    }

    @Test
    void タグ提案のtagsが配列でなければタグなしで保存する() {
        world.llmReturns("{\"tags\":\"猫\"}");

        service.generateImage(requestWithSeed(1L));

        assertNull(saved(0).getTagsJson());
    }

    @Test
    void タグ提案のLLM呼び出しが失敗しても画像生成は成功する() {
        world.http.status("POST", "/api/internal/ai/generate", 400, "{\"error\":\"LLM down\"}");

        AiImageBatchResponse response = service.generateImage(requestWithSeed(1L));

        assertEquals(1, response.images().size());
        assertNull(saved(0).getTagsJson());
    }

    // --- 画像生成オプション ---

    @Test
    void 画像生成オプションを組み立てて返す() {
        world.comfyObjectInfo(List.of("a.safetensors"), List.of("euler"), List.of("normal"), List.of("anime.safetensors"));

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
        AiImageBatchResponse response = service.generateImage(requestWithSeed(1L));

        assertEquals(Base64.getEncoder().encodeToString(new byte[]{0}), response.images().get(0).dataBase64());
        assertEquals("img0.png", response.images().get(0).fileName());
        assertEquals(100L, response.images().get(0).id());
        assertEquals("image/png", response.images().get(0).mimeType());
    }

    @Test
    void ジョブ記録のペイロードにはプロンプトを含める() {
        service.generateImage(requestWithSeed(1L));

        MediaWorld.JobCreate created = world.jobCreates().get(0);
        assertEquals("comfyui_image", created.type());
        assertEquals("a cat", created.requestPayload().get("prompt").asText());
        assertEquals(1, created.requestPayload().size());
        assertEquals("Bearer token", world.http.requests("POST", "/api/internal/ai/generation-jobs").get(0)
                .header("Authorization"), "呼び出し元のBearerトークンを転送する");
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
        JsonNode node = new ObjectMapper().valueToTree(response).get("failedRepeats");
        assertNotNull(node, "レスポンスに failedRepeats がありません(失敗したリピート数を示せていない)");
        return node.asInt();
    }

    /** generation_jobs へ記録した結果ペイロード(JSON)。 */
    private JsonNode jobResult(String status) {
        List<MediaWorld.JobUpdate> updates = world.jobUpdates(status);
        assertEquals(1, updates.size());
        assertEquals(9L, updates.get(0).jobId());
        return updates.get(0).payload();
    }

    /** n回目(1起点)の/prompt投入だけを失敗させる。 */
    private void failPromptOn(java.util.function.IntPredicate failing, int status) {
        world.http.on("POST", "/prompt", req -> failing.test(world.http.requests("POST", "/prompt").size())
                ? FakeHttpServer.Response.json(status, "{\"error\":\"VRAM\"}") : null);
    }

    // --- AC1: batchSize × batchCount 枚が1リクエストで返る ---

    @Test
    void batchSize2かつbatchCount3なら6枚返り3リピート実行される() {
        world.comfyReturns(2);

        AiImageBatchResponse response = service.generateImage(
                requestFromJson("{\"prompt\":\"a cat\",\"batchSize\":2,\"batchCount\":3,\"projectId\":1}"));

        assertEquals(6, response.images().size(), "batchSize × batchCount 枚が返らなければならない");
        assertEquals(3, world.comfyWorkflows().size(), "プロバイダ呼び出しはリピート回数ぶん");
        assertEquals(6, world.savedImages.size(), "生成した全画像が1行ずつ保存される");
        assertEquals(0, failedRepeats(response));
    }

    // --- AC2: 同一リピート内は同じseed・異なるbatchIndex、リピート間はseedが違う ---

    @Test
    void 同一リピート内は同じseedで異なるbatchIndexになりリピート間はseedが異なる() {
        world.comfyReturns(2);

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
        AiImageBatchResponse response = service.generateImage(requestFromJson(
                "{\"prompt\":\"a cat\",\"seed\":12345,\"batchCount\":3,\"projectId\":1}"));

        assertEquals(List.of(12345L, 12346L, 12347L), world.allComfyParams().stream().map(ComfyParams::seed).toList());
        assertEquals(List.of(12345L, 12346L, 12347L),
                response.images().stream().map(AiImageResponse::seed).toList());
    }

    // --- AC4: seed未指定時はリピートごとに新しいランダムseed ---

    @Test
    void seed未指定時はリピートごとに異なるランダムseedを引く() {
        AiImageBatchResponse response = service.generateImage(
                requestFromJson("{\"prompt\":\"a cat\",\"batchCount\":3,\"projectId\":1}"));

        List<Long> seeds = response.images().stream().map(AiImageResponse::seed).toList();
        assertEquals(3, seeds.stream().distinct().count(), "3リピートのseedは互いに異なる: " + seeds);
        seeds.forEach(seed -> assertTrue(seed != null && seed >= 0, "seedは0以上の実値: " + seed));
    }

    // --- AC5: batchCount省略時の後方互換 ---

    @Test
    void batchCount省略時は従来どおり1リピートだけ実行する() {
        world.comfyReturns(2);

        AiImageBatchResponse response = service.generateImage(
                requestFromJson("{\"prompt\":\"a cat\",\"batchSize\":2,\"projectId\":1}"));

        assertEquals(2, response.images().size());
        assertEquals(1, world.comfyWorkflows().size());
    }

    // --- AC6: batchSize=16(COMFYUI) ---

    @Test
    void COMFYUIではbatchSize16を1リピートで生成する() {
        world.comfyReturns(16);

        AiImageBatchResponse response = service.generateImage(
                requestFromJson("{\"prompt\":\"a cat\",\"batchSize\":16,\"projectId\":1}"));

        assertEquals(16, response.images().size());
        assertEquals(16, comfy().batchSize());
    }

    // --- AC10: CHATGPTはプロバイダ別上限(n ≤ 10) ---

    @Test
    void CHATGPTでbatchSizeが10を超えると生成を開始せず失敗する() {
        world.providerIs(1L, "CHATGPT");

        AiImageRequest imageRequest =
                requestFromJson("{\"prompt\":\"a cat\",\"batchSize\":11,\"projectId\":1}");

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.generateImage(imageRequest));

        assertTrue(e.getMessage().contains("CHATGPT"),
                "エラーメッセージにプロバイダ名を含める: " + e.getMessage());
        assertTrue(e.getMessage().contains("10"),
                "エラーメッセージに上限値を含める: " + e.getMessage());
        assertTrue(world.http.requests("POST", "/images/generations").isEmpty());
        assertTrue(world.savedImages.isEmpty());
        assertTrue(world.jobCreates().isEmpty());
    }

    @Test
    void CHATGPTでもbatchSize10までは生成する() {
        world.providerIs(1L, "CHATGPT");
        world.chatGptReturns(10);

        AiImageBatchResponse response = service.generateImage(
                requestFromJson("{\"prompt\":\"a cat\",\"batchSize\":10,\"projectId\":1}"));

        assertEquals(10, response.images().size());
    }

    @Test
    void COMFYUIではbatchSize11でも上限に触れない() {
        world.comfyReturns(11);

        AiImageBatchResponse response = service.generateImage(
                requestFromJson("{\"prompt\":\"a cat\",\"batchSize\":11,\"projectId\":1}"));

        assertEquals(11, response.images().size());
    }

    // --- AC9: 合計上限は設けない ---

    @Test
    void batchSize16かつbatchCount16は拒否せず受理する() {
        world.comfyReturns(16);

        AiImageBatchResponse response = service.generateImage(requestFromJson(
                "{\"prompt\":\"a cat\",\"batchSize\":16,\"batchCount\":16,\"projectId\":1}"));

        assertEquals(256, response.images().size(), "合計上限は設けないため256枚まで受理する");
    }

    // --- AC14: 部分失敗は成功分を返す ---

    @Test
    void 途中のリピートが失敗しても成功分を返し失敗リピート数を示す() {
        failPromptOn(n -> n == 2, 500);

        AiImageBatchResponse response = service.generateImage(
                requestFromJson("{\"prompt\":\"a cat\",\"batchCount\":3,\"projectId\":1}"));

        assertEquals(2, response.images().size(), "成功したリピートの画像は捨てない");
        assertEquals(1, failedRepeats(response), "失敗したリピート数を結果に示す");
        assertEquals(2, world.savedImages.size());
    }

    @Test
    void 部分失敗でもジョブは完了として成功数と失敗数を記録する() {
        world.comfyReturns(2);
        failPromptOn(n -> n == 2, 500);

        service.generateImage(requestFromJson(
                "{\"prompt\":\"a cat\",\"batchSize\":2,\"batchCount\":3,\"projectId\":1}"));

        JsonNode result = jobResult("done");
        assertEquals("4", result.path("count").asText(), "countは総枚数");
        assertEquals("2", result.path("succeededRepeats").asText());
        assertEquals("1", result.path("failedRepeats").asText());
    }

    @Test
    void 全リピートが失敗したらジョブを失敗として記録し例外を投げ直す() {
        world.http.status("POST", "/prompt", 500, "{\"error\":\"boom\"}");

        AiImageRequest imageRequest =
                requestFromJson("{\"prompt\":\"a cat\",\"batchCount\":3,\"projectId\":1}");

        assertThrows(AiServiceException.class, () -> service.generateImage(imageRequest));

        assertEquals(1, world.jobUpdates("failed").size());
        assertTrue(world.savedImages.isEmpty());
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
        world.http.status("POST", "/prompt", 500, "{\"error\":\"checkpoint not found: x.safetensors\"}");

        AiImageRequest imageRequest =
                requestFromJson("{\"prompt\":\"a cat\",\"batchCount\":16,\"projectId\":1}");

        assertThrows(AiServiceException.class, () -> service.generateImage(imageRequest));

        assertEquals(2, world.http.requests("POST", "/prompt").size());
    }

    /**
     * 成功したあとに連続失敗した場合も、それまでの成功分は捨てない(Requirement 10)。
     * 打ち切ったぶんのリピートは<b>失敗として数える</b>ので、
     * 成功リピート数 + 失敗リピート数 は常に要求したリピート回数と一致する。
     */
    @Test
    void 打ち切っても成功分は返し打ち切ったリピートも失敗として数える() {
        failPromptOn(n -> n >= 2, 500);

        AiImageBatchResponse response = service.generateImage(
                requestFromJson("{\"prompt\":\"a cat\",\"batchCount\":8,\"projectId\":1}"));

        assertEquals(1, response.images().size(), "成功した1リピート分の画像は返す");
        assertEquals(7, failedRepeats(response),
                "打ち切った5回を含め、成功しなかった7リピートを失敗として数える");
        assertEquals(3, world.http.requests("POST", "/prompt").size());
    }

    /** 打ち切ったことと、実際に何回試したのかがジョブ履歴から追えること。 */
    @Test
    void 打ち切りはジョブ履歴に試行回数と打ち切りの事実として残る() {
        failPromptOn(n -> n >= 2, 500);

        service.generateImage(
                requestFromJson("{\"prompt\":\"a cat\",\"batchCount\":8,\"projectId\":1}"));

        JsonNode result = jobResult("done");
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
        failPromptOn(n -> n % 2 == 0, 500);

        AiImageBatchResponse response = service.generateImage(
                requestFromJson("{\"prompt\":\"a cat\",\"batchCount\":6,\"projectId\":1}"));

        assertEquals(3, response.images().size(), "奇数回目は成功しているので3枚返る");
        assertEquals(3, failedRepeats(response));
        assertEquals(6, world.http.requests("POST", "/prompt").size());
        assertEquals("false", jobResult("done").path("aborted").asText(), "打ち切っていない");
    }

    /**
     * 全リピートが失敗したときに投げるのは<b>最初の</b>失敗にする(issue #1102 レビュー指摘)。
     * 最後の失敗を投げ直す設計は、原因を作った最初の文脈を捨ててしまう。
     * 打ち切りは「最初の失敗と同じ原因が続いた」ことを根拠に行うので、
     * 呼び出し元が最も知りたいのは最初の失敗である。
     */
    @Test
    void 全リピートが失敗したら最初の失敗を投げる() {
        AtomicInteger call = new AtomicInteger();
        world.http.on("POST", "/prompt", req -> FakeHttpServer.Response.json(
                500, "{\"error\":\"失敗" + call.incrementAndGet() + "\"}"));

        AiImageRequest imageRequest =
                requestFromJson("{\"prompt\":\"a cat\",\"batchCount\":4,\"projectId\":1}");

        AiServiceException e = assertThrows(AiServiceException.class, () -> service.generateImage(imageRequest));

        assertTrue(e.getMessage().contains("失敗1"), "最初の失敗を投げる(最後ではない): " + e.getMessage());
    }

    /**
     * 原因が異なる失敗が続いた場合、最初の失敗だけを返すと残りの文脈が消える。
     * 2件目以降の異なる失敗は{@code addSuppressed}で添えて、ログに全て残るようにする。
     */
    @Test
    void 異なる原因の失敗は最初の例外に添えて失われないようにする() {
        AtomicInteger call = new AtomicInteger();
        world.http.on("POST", "/prompt", req -> FakeHttpServer.Response.json(
                500, "{\"error\":\"失敗" + call.incrementAndGet() + "\"}"));

        AiImageRequest imageRequest =
                requestFromJson("{\"prompt\":\"a cat\",\"batchCount\":4,\"projectId\":1}");

        AiServiceException e = assertThrows(AiServiceException.class, () -> service.generateImage(imageRequest));

        assertEquals(1, e.getSuppressed().length, "2件目の失敗が添えられている");
        assertTrue(e.getSuppressed()[0].getMessage().contains("失敗2"));
    }

    // --- レビュー指摘(SUGGESTION): リピート間で変わらない既定値は1回だけ引く ---

    /**
     * プロジェクト既定値の解決はDB往復を伴う({@code ProjectImageDefaultsResolver}・
     * {@code ComfyUiModelService})。リピート間で変わらない値なので、
     * {@code batchCount}回引き直す必要は無い(issue #1102 レビュー指摘)。
     *
     * <p>品質プロンプトは元の実装で最も回数が多く、{@code batchCount=16}のとき
     * 18回(禁止コンテンツ検査1 + タグ提案1 + リピート16)引いていた。
     * 解決のたびにproject-serviceへの存在確認とDBの読み出しが起きるので、その回数が
     * リピート回数に依らないことで確かめる。
     */
    @Test
    void リピート間で変わらない既定値はリピート回数によらず1回だけ引く() {
        service.generateImage(requestFromJson("{\"prompt\":\"a cat\",\"batchCount\":1,\"projectId\":1}"));
        int singleRepeat = world.http.requests("GET", "/api/internal/project/projects/1").size();

        try (MediaWorld sixteen = new MediaWorld()) {
            sixteen.settingsFor(1L);
            sixteen.imageGenerationService.generateImage(
                    requestFromJson("{\"prompt\":\"a cat\",\"batchCount\":16,\"projectId\":1}"));

            assertEquals(singleRepeat, sixteen.http.requests("GET", "/api/internal/project/projects/1").size(),
                    "既定値の解決回数はbatchCountに依らない");
            assertEquals(16, sixteen.comfyWorkflows().size());
        }
    }

    /** 1回だけ引くようにしても、リピートごとに変わるseed以外は全リピートで同じ値になる。 */
    @Test
    void 既定値を1回だけ引いても全リピートに同じ値が渡る() {
        service.generateImage(
                requestFromJson("{\"prompt\":\"a cat\",\"seed\":100,\"batchCount\":3,\"projectId\":1}"));

        List<ComfyParams> params = world.allComfyParams();
        assertEquals(3, params.size());
        for (ComfyParams p : params) {
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
        service.generateImage(
                requestFromJson("{\"prompt\":\"a cat\",\"batchCount\":3,\"projectId\":1}"));

        assertEquals(1, world.http.requests("POST", "/api/internal/ai/generate").size());
    }

    /**
     * 同じ例外インスタンスを自分自身に添えると{@link IllegalArgumentException}になるため、2件目以降の失敗は
     * 最初の失敗と別のインスタンスのときだけ添える。DB(リポジトリ)が毎回同じ例外インスタンスを投げる状態で、
     * 最初の失敗に自分自身が添えられずに、元の例外がそのまま投げ直されることを確かめる。
     */
    @Test
    void 同じ例外インスタンスが続けて投げられても自分自身を添えずに最初の失敗を投げる() {
        IllegalStateException sameFailure = new IllegalStateException("DBが落ちている");
        org.mockito.Mockito.when(world.generatedImageRepository.save(org.mockito.ArgumentMatchers.any(GeneratedImage.class)))
                .thenThrow(sameFailure);
        AiImageRequest imageRequest =
                requestFromJson("{\"prompt\":\"a cat\",\"batchCount\":3,\"projectId\":1}");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.generateImage(imageRequest));

        assertTrue(e == sameFailure, "最初の失敗がそのまま投げられる");
        assertEquals(0, e.getSuppressed().length, "自分自身は添えない");
        assertEquals(2, world.http.requests("POST", "/prompt").size(), "2回続けて失敗したので打ち切る");
    }
}
