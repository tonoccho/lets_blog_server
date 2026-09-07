package com.letsblog.media.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * ComfyUiClientの回帰テスト(issue #1101)。MockRestServiceServerでComfyUIの
 * /prompt・/history・/view・/api/interrupt・/object_info/* を模擬する。
 *
 * <p>#1101の中心は「ワークフローに埋め込まれるseedは、呼び出し側が決めた実値そのものである」
 * こと。同じseedを渡せば同じワークフローJSONが組み上がる(=ComfyUIが同じ画像を返す)ことを
 * 固定する。クライアント内部でランダムseedを作らないので、seedが未解決(null)のまま
 * 呼ばれたら黙って進まず失敗する。
 */
@DisplayName("media-service: ComfyUIワークフロー組み立てとseed(issue #1101)")
class ComfyUiClientTest {

    private static final String BASE_URL = "http://comfyui.test";
    private static final String DEFAULT_CHECKPOINT = "default.safetensors";

    private MockRestServiceServer server;
    private ComfyUiClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        client = new ComfyUiClient(builder, new StubConfigProvider(), DEFAULT_CHECKPOINT);
    }

    private static ComfyUiGenerationParams params(Long seed) {
        return new ComfyUiGenerationParams(
                "a cat", "blurry", 20, 7.0, "euler", "normal", seed, 512, 768, 1,
                "checkpoint.safetensors", null, null);
    }

    /** /prompt に投入されたワークフローJSONを取り出せるよう、リクエストボディを控える。 */
    private final StringBuilder submittedWorkflow = new StringBuilder();

    private void expectGeneration(int imageCount) {
        server.expect(requestTo(BASE_URL + "/prompt"))
                .andExpect(request -> submittedWorkflow.append(
                        new String(((org.springframework.mock.http.client.MockClientHttpRequest) request)
                                .getBodyAsBytes())))
                .andRespond(withSuccess("{\"prompt_id\":\"p1\"}", MediaType.APPLICATION_JSON));
        StringBuilder images = new StringBuilder();
        for (int i = 0; i < imageCount; i++) {
            if (i > 0) {
                images.append(',');
            }
            images.append("{\"filename\":\"img").append(i)
                    .append(".png\",\"subfolder\":\"sub\",\"type\":\"output\"}");
        }
        server.expect(manyTimes(), requestTo(BASE_URL + "/history/p1"))
                .andRespond(withSuccess(
                        "{\"p1\":{\"outputs\":{\"9\":{\"images\":[" + images + "]}}}}",
                        MediaType.APPLICATION_JSON));
        for (int i = 0; i < imageCount; i++) {
            server.expect(requestTo(
                            BASE_URL + "/view?filename=img" + i + ".png&subfolder=sub&type=output"))
                    .andRespond(withSuccess(new byte[]{(byte) i, 2, 3}, MediaType.IMAGE_PNG));
        }
        server.expect(requestTo(BASE_URL + "/api/interrupt")).andRespond(withSuccess());
    }

    private JsonNode submittedGraph() throws Exception {
        return mapper.readTree(submittedWorkflow.toString()).get("prompt");
    }

    @Test
    void 呼び出し側が決めたseedをそのままワークフローへ埋め込む() throws Exception {
        expectGeneration(1);

        List<ComfyUiImage> images = client.generateImage(params(987654321L));

        assertEquals(1, images.size());
        assertEquals("img0.png", images.get(0).fileName());
        assertEquals("image/png", images.get(0).mimeType());
        assertEquals(987654321L, submittedGraph().path("3").path("inputs").path("seed").asLong());
        server.verify();
    }

    @Test
    void 同じseedを渡せば同じワークフローJSONになる() throws Exception {
        expectGeneration(1);
        client.generateImage(params(4242L));
        String first = submittedGraph().toString();

        setUp();
        submittedWorkflow.setLength(0);
        expectGeneration(1);
        client.generateImage(params(4242L));

        assertEquals(first, submittedGraph().toString());
    }

    @Test
    void seedが未解決のままなら生成せずに失敗する() {
        AiServiceException e = assertThrows(
                AiServiceException.class, () -> client.generateImage(params(null)));
        assertTrue(e.getMessage().contains("seed"), e.getMessage());
    }

    @Test
    void batchSize分の画像をすべて取得する() {
        expectGeneration(3);

        List<ComfyUiImage> images = client.generateImage(new ComfyUiGenerationParams(
                "a cat", "blurry", 20, 7.0, "euler", "normal", 1L, 512, 512, 3,
                "checkpoint.safetensors", null, null));

        assertEquals(3, images.size());
        server.verify();
    }

    @Test
    void checkpoint未指定なら設定既定のチェックポイントを使う() throws Exception {
        expectGeneration(1);

        client.generateImage(new ComfyUiGenerationParams(
                "a cat", "blurry", 20, 7.0, "euler", "normal", 5L, 512, 512, 1, null, null, null));

        assertEquals(DEFAULT_CHECKPOINT,
                submittedGraph().path("4").path("inputs").path("ckpt_name").asText());
    }

    @Test
    void LoRA指定時はLoraLoaderノードを挟む() throws Exception {
        expectGeneration(1);

        client.generateImage(new ComfyUiGenerationParams(
                "a cat", "blurry", 20, 7.0, "euler", "normal", 5L, 512, 512, 1,
                "checkpoint.safetensors", "anime.safetensors", 0.6));

        JsonNode lora = submittedGraph().path("10");
        assertEquals("LoraLoader", lora.path("class_type").asText());
        assertEquals(0.6, lora.path("inputs").path("strength_model").asDouble());
        assertEquals("10", submittedGraph().path("3").path("inputs").path("model").get(0).asText());
    }

    @Test
    void LoRAの強度未指定なら1_0を使う() throws Exception {
        expectGeneration(1);

        client.generateImage(new ComfyUiGenerationParams(
                "a cat", "blurry", 20, 7.0, "euler", "normal", 5L, 512, 512, 1,
                "checkpoint.safetensors", "anime.safetensors", null));

        assertEquals(1.0, submittedGraph().path("10").path("inputs").path("strength_clip").asDouble());
    }

    @Test
    void LoRA名が空白なら無視する() throws Exception {
        expectGeneration(1);

        client.generateImage(new ComfyUiGenerationParams(
                "a cat", "blurry", 20, 7.0, "euler", "normal", 5L, 512, 512, 1,
                "checkpoint.safetensors", "  ", null));

        assertTrue(submittedGraph().path("10").isMissingNode());
    }

    @Test
    void 画像のsubfolderとtypeが省略された履歴でも取得できる() {
        server.expect(requestTo(BASE_URL + "/prompt"))
                .andRespond(withSuccess("{\"prompt_id\":\"p1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/history/p1"))
                .andRespond(withSuccess(
                        "{\"p1\":{\"outputs\":{\"9\":{\"images\":[{\"filename\":\"only.png\"}]}}}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/view?filename=only.png&subfolder=&type=output"))
                .andRespond(withSuccess(new byte[]{7}, MediaType.IMAGE_PNG));
        server.expect(requestTo(BASE_URL + "/api/interrupt")).andRespond(withSuccess());

        List<ComfyUiImage> images = client.generateImage(params(5L));

        assertEquals("only.png", images.get(0).fileName());
    }

    @Test
    void 履歴がまだ出力を持たない間はポーリングを続ける() {
        server.expect(requestTo(BASE_URL + "/prompt"))
                .andRespond(withSuccess("{\"prompt_id\":\"p1\"}", MediaType.APPLICATION_JSON));
        // 履歴そのものが空(本文なし)→ まだ p1 のエントリが無い → outputs が空、の順に進む。
        server.expect(requestTo(BASE_URL + "/history/p1")).andRespond(withSuccess());
        server.expect(requestTo(BASE_URL + "/history/p1")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/history/p1"))
                .andRespond(withSuccess("{\"p1\":{}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/history/p1"))
                .andRespond(withSuccess("{\"p1\":{\"outputs\":{}}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/history/p1"))
                .andRespond(withSuccess(
                        "{\"p1\":{\"outputs\":{\"8\":{},\"9\":{\"images\":[]},"
                                + "\"10\":{\"images\":[{\"filename\":\"late.png\"}]}}}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/view?filename=late.png&subfolder=&type=output"))
                .andRespond(withSuccess(new byte[]{7}, MediaType.IMAGE_PNG));
        server.expect(requestTo(BASE_URL + "/api/interrupt")).andRespond(withSuccess());

        assertEquals("late.png", client.generateImage(params(5L)).get(0).fileName());
    }

    @Test
    void ジョブ投入が失敗したらAiServiceExceptionにする() {
        server.expect(requestTo(BASE_URL + "/prompt"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("boom"));

        AiServiceException e = assertThrows(
                AiServiceException.class, () -> client.generateImage(params(5L)));
        assertTrue(e.getMessage().contains("ジョブ投入"), e.getMessage());
    }

    @Test
    void VRAMクリアの失敗は生成結果に影響しない() {
        server.expect(requestTo(BASE_URL + "/prompt"))
                .andRespond(withSuccess("{\"prompt_id\":\"p1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/history/p1"))
                .andRespond(withSuccess(
                        "{\"p1\":{\"outputs\":{\"9\":{\"images\":[{\"filename\":\"a.png\"}]}}}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/view?filename=a.png&subfolder=&type=output"))
                .andRespond(withSuccess(new byte[]{1}, MediaType.IMAGE_PNG));
        server.expect(requestTo(BASE_URL + "/api/interrupt"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertEquals(1, client.generateImage(params(5L)).size());
    }

    @Test
    void チェックポイント一覧を取得する() {
        server.expect(requestTo(BASE_URL + "/object_info/CheckpointLoaderSimple"))
                .andRespond(withSuccess(
                        "{\"CheckpointLoaderSimple\":{\"input\":{\"required\":{\"ckpt_name\":[[\"a.safetensors\","
                                + "\"b.safetensors\"]]}}}}", MediaType.APPLICATION_JSON));

        assertEquals(List.of("a.safetensors", "b.safetensors"), client.listCheckpoints());
    }

    @Test
    void チェックポイント一覧の取得失敗はAiServiceExceptionにする() {
        server.expect(requestTo(BASE_URL + "/object_info/CheckpointLoaderSimple"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("ng"));

        assertThrows(AiServiceException.class, () -> client.listCheckpoints());
    }

    @Test
    void 応答が空ならチェックポイント一覧は空になる() {
        server.expect(requestTo(BASE_URL + "/object_info/CheckpointLoaderSimple"))
                .andRespond(withSuccess());

        assertTrue(client.listCheckpoints().isEmpty());
    }

    @Test
    void サンプラーとスケジューラーの一覧を取得する() {
        server.expect(manyTimes(), requestTo(BASE_URL + "/object_info/KSampler"))
                .andRespond(withSuccess(
                        "{\"KSampler\":{\"input\":{\"required\":{\"sampler_name\":[[\"euler\"]],"
                                + "\"scheduler\":[[\"normal\",\"karras\"]]}}}}", MediaType.APPLICATION_JSON));

        assertEquals(List.of("euler"), client.listSamplers());
        assertEquals(List.of("normal", "karras"), client.listSchedulers());
    }

    @Test
    void サンプラー一覧の取得失敗はAiServiceExceptionにする() {
        server.expect(requestTo(BASE_URL + "/object_info/KSampler"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("ng"));

        assertThrows(AiServiceException.class, () -> client.listSamplers());
    }

    @Test
    void スケジューラー一覧の取得失敗はAiServiceExceptionにする() {
        server.expect(requestTo(BASE_URL + "/object_info/KSampler"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("ng"));

        assertThrows(AiServiceException.class, () -> client.listSchedulers());
    }

    @Test
    void 応答が空ならサンプラーとスケジューラーの一覧は空になる() {
        server.expect(manyTimes(), requestTo(BASE_URL + "/object_info/KSampler"))
                .andRespond(withSuccess());

        assertTrue(client.listSamplers().isEmpty());
        assertTrue(client.listSchedulers().isEmpty());
    }

    @Test
    void LoRA一覧を取得する() {
        server.expect(requestTo(BASE_URL + "/object_info/LoraLoader"))
                .andRespond(withSuccess(
                        "{\"LoraLoader\":{\"input\":{\"required\":{\"lora_name\":[[\"anime.safetensors\"]]}}}}",
                        MediaType.APPLICATION_JSON));

        assertEquals(List.of("anime.safetensors"), client.listLoras());
    }

    @Test
    void LoraLoaderが無いComfyUIでは空のLoRA一覧を返す() {
        server.expect(requestTo(BASE_URL + "/object_info/LoraLoader"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body("no node"));

        assertTrue(client.listLoras().isEmpty());
    }

    @Test
    void 応答が空ならLoRA一覧は空になる() {
        server.expect(requestTo(BASE_URL + "/object_info/LoraLoader"))
                .andRespond(withSuccess());

        assertTrue(client.listLoras().isEmpty());
    }

    @Test
    void プロンプトとサイズをワークフローへ反映する() throws Exception {
        expectGeneration(1);

        client.generateImage(params(5L));

        JsonNode graph = submittedGraph();
        assertEquals("a cat", graph.path("6").path("inputs").path("text").asText());
        assertEquals("blurry", graph.path("7").path("inputs").path("text").asText());
        assertEquals(512, graph.path("5").path("inputs").path("width").asInt());
        assertEquals(768, graph.path("5").path("inputs").path("height").asInt());
        assertFalse(graph.path("3").path("inputs").path("seed").isNull());
    }

    private static final class StubConfigProvider implements ImageGenerationConfigProvider {
        @Override
        public String comfyUiBaseUrl() {
            return BASE_URL;
        }

        @Override
        public String chatGptApiKey() {
            return "unused";
        }

        @Override
        public String chatGptBaseUrl() {
            return BASE_URL;
        }
    }

    // ------------------------------------------------------------------
    // issue #1102: ポーリング上限をバッチのサイズに応じてスケールさせる
    // ------------------------------------------------------------------

    /**
     * 1投入あたりの待ち時間の上限は、投入した枚数に比例して伸びなければならない
     * (issue #1102 Requirements 6)。ただし<b>従来の120秒を下回らない</b>
     * ({@link #ポーリング上限は従来の120秒を下回らない()})。
     *
     * <p><b>比例部分の値は実測から導く</b>(#1102 レビュー指摘)。512×512・20ステップ・単一GPU
     * (lbs-ollamaとGPUを共有)の実測は 1枚3.8秒 / 4枚5.3秒 / 16枚17秒 / 32枚34秒で、
     * 限界コストは約1.06秒/枚、枚数に比例しない固定部分は約3秒。
     * {@code 60 + 8 × batchSize} 秒は、どのbatchSizeでも実測の10倍以上の余裕を持つ。
     *
     * <p>比例部分が下限120秒を上回るのは{@code batchSize >= 8}から。そこから先が
     * 「枚数に比例して伸びる」ことをここで固定する。鎖全体の整合は
     * {@link ImageGenerationTimeoutChainTest} が見る。
     */
    @Test
    void ポーリング上限はbatchSizeに比例して伸びる() {
        assertEquals(124, ComfyUiClient.maxPollAttempts(8), "8枚は60+8×8=124秒");
        assertEquals(188, ComfyUiClient.maxPollAttempts(16), "16枚は実測17秒に対し188秒(11.1倍)");
        assertTrue(ComfyUiClient.maxPollAttempts(16) > ComfyUiClient.maxPollAttempts(8),
                "枚数が増えれば予算も増えなければならない");
    }

    /**
     * <b>#1102以前に実行できたリクエストのポーリング予算を縮めてはならない</b>
     * (#1102 レビュー差し戻し、note_4592)。
     *
     * <p>#1102以前の{@code batchSize}の上限は4で、予算は{@code MAX_POLL_ATTEMPTS = 120}の
     * 固定だった。比例式{@code 60 + 8 × batchSize}をそのまま当てると 1枚68秒 / 2枚76秒 /
     * 4枚92秒となり、<b>以前から可能だった入力の全域で予算が短くなる</b>。
     * {@code width}/{@code height}は最大2048、{@code steps}は最大150まで受理するので、
     * 512×512・20ステップの実測だけを根拠にこの範囲を縮めると、以前なら通っていた
     * 重いリクエストが新たに504になりうる。本Issueはbatchの追加であって、既存の
     * 単一画像生成を厳しくするものではない。
     *
     * <p>解像度・ステップ数を予算に織り込む設計変更は#1111。ここでは下限だけを固定する。
     */
    @Test
    void ポーリング上限は従来の120秒を下回らない() {
        assertTrue(ComfyUiClient.maxPollSeconds(1) >= 120,
                "batchSize=1の予算" + ComfyUiClient.maxPollSeconds(1) + "秒が#1102以前の120秒を下回っています");
        assertTrue(ComfyUiClient.maxPollSeconds(2) >= 120,
                "batchSize=2の予算" + ComfyUiClient.maxPollSeconds(2) + "秒が#1102以前の120秒を下回っています");
        assertTrue(ComfyUiClient.maxPollSeconds(4) >= 120,
                "batchSize=4の予算" + ComfyUiClient.maxPollSeconds(4) + "秒が#1102以前の120秒を下回っています");

        assertEquals(120, ComfyUiClient.maxPollAttempts(1), "1枚は下限の120秒");
        assertEquals(120, ComfyUiClient.maxPollAttempts(2), "2枚は下限の120秒");
        assertEquals(120, ComfyUiClient.maxPollAttempts(4), "4枚は下限の120秒");
    }

    @Test
    void batchSizeが未指定や0以下なら1枚として扱う() {
        assertEquals(120, ComfyUiClient.maxPollAttempts(null));
        assertEquals(120, ComfyUiClient.maxPollAttempts(0));
        assertEquals(120, ComfyUiClient.maxPollAttempts(-3));
    }

    /**
     * ポーリング上限を「秒」で公開する。回数×間隔の掛け算を呼び出し側(タイムアウトの鎖を
     * 検証するテスト)が再現すると、{@code POLL_INTERVAL_MS}を変えたときに黙って
     * ずれるため、変換はクライアント自身に持たせる。
     */
    @Test
    void ポーリング上限を秒でも取得できる() {
        assertEquals(120, ComfyUiClient.maxPollSeconds(1));
        assertEquals(188, ComfyUiClient.maxPollSeconds(16));
    }

    /**
     * 完了しないまま上限に達したらタイムアウトとして失敗し、メッセージにprompt_idを含める
     * (#1101から変えない挙動)。ポーリング間隔はテスト専用のコンストラクタで詰める。
     */
    @Test
    void 完了しないままポーリング上限に達したらprompt_id付きで失敗する() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer fastServer = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        ComfyUiClient fastClient = new ComfyUiClient(builder, new StubConfigProvider(), DEFAULT_CHECKPOINT, 1);
        fastServer.expect(requestTo(BASE_URL + "/prompt"))
                .andRespond(withSuccess("{\"prompt_id\":\"p9\"}", MediaType.APPLICATION_JSON));
        fastServer.expect(manyTimes(), requestTo(BASE_URL + "/history/p9"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        AiServiceException e = assertThrows(AiServiceException.class,
                () -> fastClient.generateImage(params(11L)));

        assertTrue(e.getMessage().contains("p9"), e.getMessage());
        assertTrue(e.getMessage().contains("タイムアウト"), e.getMessage());
    }
}
