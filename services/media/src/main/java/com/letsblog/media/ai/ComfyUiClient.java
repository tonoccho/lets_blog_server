package com.letsblog.media.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.letsblog.media.config.LegacyJacksonRestClientConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ComfyUIのAPI(/prompt, /history, /view, /object_info)を呼び出し、txt2img画像を生成するクライアント。
 * ComfyUIは非同期のキュー方式のため、/prompt投入後 /history をポーリングして完了を待つ。
 * 処理完了後はVRAMをクリアし、メモリリークを防止する。
 * baseUrlはImageGenerationConfigProviderから呼び出しの都度取得する(issue #531でWeb管理画面の
 * システム設定から変更可能になったため、LlmClientと同様に構築時に固定値として保持しない)。
 * seedの実値は決めない(issue #1101)。渡されたparams.seed()をそのままワークフローへ埋め込む。
 */
@Component
@Slf4j
public class ComfyUiClient implements ImageGenerationProvider {

    private static final int POLL_INTERVAL_MS = 1000;

    /**
     * 投入したバッチの枚数によらず必要な待ち時間(秒相当の回数)。キュー待ち・モデルのロード・
     * VAEデコードなど、枚数に比例しない部分の取り分。
     *
     * <p>実測(512×512・20ステップ・単一GPU)の固定部分は約3秒だが、実測時は
     * チェックポイントが常駐していた。コールドな読み込みと、GPUを共有する
     * {@code lbs-ollama}(docker-compose.yml 920-995行)の推論待ちを見込んで60秒とする
     * (実測の約20倍)。
     */
    static final int BASE_POLL_ATTEMPTS = 60;

    /**
     * 1枚あたりに上乗せする待ち時間(秒相当の回数)。
     *
     * <p><b>値の根拠(issue #1102 レビュー指摘)</b>。実測(512×512・20ステップ・単一GPU)は
     * 1枚3.8秒 / 4枚5.3秒 / 16枚17秒 / 32枚34秒で、限界コストは約1.06秒/枚。
     * 8秒/枚はその約7.5倍で、固定分(60秒)と合わせた1投入あたりの予算は
     * どのbatchSizeでも実測の10倍以上になる。
     *
     * <pre>
     *   batchSize=1  : 120秒 (下限。比例式では68秒)
     *   batchSize=4  : 120秒 (下限。比例式では92秒)
     *   batchSize=8  : 124秒 (実測7秒前後の17倍)
     *   batchSize=16 : 188秒 (実測17秒の11.1倍)
     * </pre>
     *
     * <p>比例式が{@link #MIN_POLL_ATTEMPTS}(120)を上回るのは{@code batchSize >= 8}から。
     * それ未満は下限が効く。
     *
     * <p>#1102の当初実装は{@code 60 + 60 × batchSize}(16枚で1020秒=実測の60倍)だった。
     * この過大な1枚あたりの取り分が最悪ケース4.5時間を生み、gatewayに5時間の
     * {@code response-timeout}を要求し、nginxの{@code location /api/}(1200s)と
     * 矛盾させていた。<b>1枚あたりの取り分を実測へ寄せることが、鎖全体を1時間以内へ
     * 収める根拠</b>である。縮めてよいのは枚数に比例する部分だけで、#1102以前から
     * 実行できた範囲の予算は{@link #MIN_POLL_ATTEMPTS}が守る。
     *
     * <p><b>見直しが要るとき</b>: {@code AiImageRequest}の{@code batchSize}/
     * {@code batchCount}の上限を上げたとき、あるいは既定の解像度・ステップ数を
     * 大きくしたとき。上限は
     * {@code ImageGenerationTimeoutChainTest}(media)が
     * {@code nginx >= gateway >= mediaの最悪ケース}として機械的に見ているので、
     * そこが落ちたら3層すべて(このクラス /
     * {@code services/gateway/src/main/resources/application.yml} の{@code ai-image} /
     * {@code infra/nginx/conf.d/default.conf} の{@code location = /api/ai/image})を
     * 揃えて直すこと。
     */
    static final int POLL_ATTEMPTS_PER_IMAGE = 8;

    /**
     * 投入したバッチの枚数によらず必ず与える最低の待ち時間(秒相当の回数)。
     *
     * <p><b>値の由来</b>: #1102以前の{@code MAX_POLL_ATTEMPTS = 120}。当時
     * {@code batchSize}の上限は4で、予算はbatchSizeによらずこの120秒固定だった。
     * 比例式{@code BASE + PER × batchSize}をそのまま当てると 1枚68秒 / 2枚76秒 /
     * 4枚92秒となり、<b>#1102以前に実行できた入力の全域で予算が短くなる</b>。
     * {@code AiImageRequest}は{@code width}/{@code height}を最大2048、{@code steps}を
     * 最大150まで受理するので、512×512・20ステップの実測だけを根拠にこの範囲を縮めると、
     * 以前なら通っていた重いリクエストが新たに504になりうる。#1102はbatchの追加であって
     * 既存の単一画像生成を厳しくするものではないため、<b>従来の120秒を割り込まない</b>
     * ことを下限として保証する(#1102 レビュー差し戻し note_4592)。
     *
     * <p>この下限は解像度・ステップ数を予算に織り込む代わりではない。重い単発生成に
     * 見合う予算をパラメータから導く設計変更は#1111で扱う。
     */
    static final int MIN_POLL_ATTEMPTS = 120;

    private final RestClient client;
    private final ImageGenerationConfigProvider configProvider;
    private final String checkpointName;
    private final int pollIntervalMs;

    @Autowired
    public ComfyUiClient(ImageGenerationConfigProvider configProvider,
                          @Value("${app.comfyui-checkpoint}") String checkpointName) {
        this(RestClient.builder(), configProvider, checkpointName);
    }

    /** テスト専用: MockRestServiceServerを介せるようRestClient.Builderを直接受け取るコンストラクタ。 */
    ComfyUiClient(RestClient.Builder builder, ImageGenerationConfigProvider configProvider, String checkpointName) {
        this(builder, configProvider, checkpointName, POLL_INTERVAL_MS);
    }

    /** テスト専用: ポーリング間隔を詰めて、タイムアウトの検証を実時間を掛けずに行うためのコンストラクタ。 */
    ComfyUiClient(RestClient.Builder builder, ImageGenerationConfigProvider configProvider,
                  String checkpointName, int pollIntervalMs) {
        LegacyJacksonRestClientConfig.preferJackson2(builder);
        this.client = builder.build();
        this.configProvider = configProvider;
        this.checkpointName = checkpointName;
        this.pollIntervalMs = pollIntervalMs;
    }

    /**
     * 1回の投入を待つポーリング回数の上限(1回あたり{@value #POLL_INTERVAL_MS}ミリ秒)。
     * 投入した枚数に比例して伸ばす(issue #1102)。ただし{@link #MIN_POLL_ATTEMPTS}
     * (=#1102以前の固定値120)を下回らせない。{@code batchSize}が未指定・0以下のときは
     * 1枚として扱う。
     */
    static int maxPollAttempts(Integer batchSize) {
        int size = batchSize != null && batchSize > 0 ? batchSize : 1;
        return Math.max(MIN_POLL_ATTEMPTS, BASE_POLL_ATTEMPTS + POLL_ATTEMPTS_PER_IMAGE * size);
    }

    /**
     * 1回の投入を待つ上限を秒で返す。回数と間隔の掛け算を呼び出し側が再現すると
     * {@code POLL_INTERVAL_MS}を変えたときに黙ってずれるため、変換はここに置く
     * (タイムアウトの鎖を検証する{@code ImageGenerationTimeoutChainTest}が使う)。
     */
    static int maxPollSeconds(Integer batchSize) {
        return maxPollAttempts(batchSize) * POLL_INTERVAL_MS / 1000;
    }

    /**
     * batch_size枚分の画像を生成する。EmptyLatentImageのbatch_sizeに応じてComfyUI側のSaveImageノードが
     * 複数ファイルを出力するため、historyのoutputs.images配列を全件取得して1枚ずつ/viewで取得する。
     * 処理完了後にVRAMをクリアする。
     */
    @Override
    public List<ComfyUiImage> generateImage(ComfyUiGenerationParams params) {
        if (params.seed() == null) {
            // issue #1101: seedの実値決定は呼び出し側(SeedResolver経由のImageGenerationService)の責務。
            // ここでランダム値を作ると、その値が呼び出し元へ返らずgenerated_images.seedがNULLになる
            // (=生成した画像を再現できない)という#1101の不具合そのものが再発する。黙って進めない。
            throw new AiServiceException(
                    "ComfyUIの画像生成にはseedの実値が必要です(呼び出し側で解決してください)", null);
        }
        String baseUrl = configProvider.comfyUiBaseUrl();
        try {
            return submitAndCollect(baseUrl, params);
        } catch (ResourceAccessException e) {
            throw unreachable(baseUrl, e);
        }
    }

    private List<ComfyUiImage> submitAndCollect(String baseUrl, ComfyUiGenerationParams params) {
        String clientId = UUID.randomUUID().toString();
        ObjectNode workflow = buildWorkflow(params);

        ObjectNode requestBody = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        requestBody.set("prompt", workflow);
        requestBody.put("client_id", clientId);

        String promptId;
        try {
            JsonNode submitResponse = client.post()
                    .uri(baseUrl + "/prompt")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(JsonNode.class);
            promptId = submitResponse.get("prompt_id").asText();
        } catch (RestClientResponseException e) {
            throw new AiServiceException("ComfyUIへのジョブ投入に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }

        List<JsonNode> outputImages = pollForResult(baseUrl, promptId, maxPollAttempts(params.batchSize()));
        List<ComfyUiImage> images = new ArrayList<>();
        for (JsonNode outputImage : outputImages) {
            String filename = outputImage.get("filename").asText();
            String subfolder = outputImage.has("subfolder") ? outputImage.get("subfolder").asText() : "";
            String type = outputImage.has("type") ? outputImage.get("type").asText() : "output";

            URI viewUri = UriComponentsBuilder.fromUriString(baseUrl)
                    .path("/view")
                    .queryParam("filename", filename)
                    .queryParam("subfolder", subfolder)
                    .queryParam("type", type)
                    .build()
                    .toUri();
            byte[] data = client.get()
                    .uri(viewUri)
                    .retrieve()
                    .body(byte[].class);

            images.add(new ComfyUiImage(filename, data, "image/png"));
        }

        clearMemory(baseUrl);

        return images;
    }

    private List<JsonNode> pollForResult(String baseUrl, String promptId, int maxPollAttempts) {
        for (int attempt = 0; attempt < maxPollAttempts; attempt++) {
            JsonNode history = client.get().uri(baseUrl + "/history/" + promptId).retrieve().body(JsonNode.class);
            JsonNode entry = history != null ? history.get(promptId) : null;

            if (entry != null && entry.has("outputs")) {
                for (JsonNode nodeOutput : entry.get("outputs")) {
                    if (nodeOutput.has("images") && nodeOutput.get("images").size() > 0) {
                        List<JsonNode> images = new ArrayList<>();
                        nodeOutput.get("images").forEach(images::add);
                        return images;
                    }
                }
            }

            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AiServiceException("ComfyUIの結果待機が中断されました", e);
            }
        }
        throw new AiServiceException("ComfyUIの画像生成がタイムアウトしました(prompt_id=" + promptId + ")", null);
    }

    /**
     * ComfyUIに現在配置されているチェックポイント一覧を取得する(GET /object_info/CheckpointLoaderSimple)。
     */
    public List<String> listCheckpoints() {
        String baseUrl = configProvider.comfyUiBaseUrl();
        try {
            JsonNode response = client.get()
                    .uri(baseUrl + "/object_info/CheckpointLoaderSimple")
                    .retrieve().body(JsonNode.class);
            List<String> checkpoints = new ArrayList<>();
            if (response == null) {
                return checkpoints;
            }
            JsonNode names = response.path("CheckpointLoaderSimple")
                    .path("input").path("required").path("ckpt_name").path(0);
            for (JsonNode name : names) {
                checkpoints.add(name.asText());
            }
            return checkpoints;
        } catch (ResourceAccessException e) {
            throw unreachable(baseUrl, e);
        } catch (RestClientResponseException e) {
            throw new AiServiceException("ComfyUIチェックポイント一覧の取得に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
    }

    /**
     * ComfyUIが対応しているサンプラー名の一覧を取得する(GET /object_info/KSampler)。
     */
    public List<String> listSamplers() {
        String baseUrl = configProvider.comfyUiBaseUrl();
        try {
            JsonNode response = client.get()
                    .uri(baseUrl + "/object_info/KSampler")
                    .retrieve().body(JsonNode.class);
            List<String> samplers = new ArrayList<>();
            if (response == null) {
                return samplers;
            }
            JsonNode samplerNames = response.path("KSampler")
                    .path("input").path("required").path("sampler_name").path(0);
            for (JsonNode name : samplerNames) {
                samplers.add(name.asText());
            }
            return samplers;
        } catch (ResourceAccessException e) {
            throw unreachable(baseUrl, e);
        } catch (RestClientResponseException e) {
            throw new AiServiceException("ComfyUIサンプラー一覧の取得に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
    }

    /**
     * ComfyUIが対応しているスケジューラー名の一覧を取得する(GET /object_info/KSampler)。
     */
    public List<String> listSchedulers() {
        String baseUrl = configProvider.comfyUiBaseUrl();
        try {
            JsonNode response = client.get()
                    .uri(baseUrl + "/object_info/KSampler")
                    .retrieve().body(JsonNode.class);
            List<String> schedulers = new ArrayList<>();
            if (response == null) {
                return schedulers;
            }
            JsonNode schedulerNames = response.path("KSampler")
                    .path("input").path("required").path("scheduler").path(0);
            for (JsonNode name : schedulerNames) {
                schedulers.add(name.asText());
            }
            return schedulers;
        } catch (ResourceAccessException e) {
            throw unreachable(baseUrl, e);
        } catch (RestClientResponseException e) {
            throw new AiServiceException("ComfyUIスケジューラー一覧の取得に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
    }

    /**
     * ComfyUIに配置されているLoRAモデルの一覧を取得する(GET /object_info/LoraLoader)。
     * LoraLoaderノードが未実装のComfyUI環境(HTTPエラー)では空リストを返す。到達できない場合は空リストへ丸めず{@link AiServiceException}(#1126)。
     */
    public List<String> listLoras() {
        String baseUrl = configProvider.comfyUiBaseUrl();
        try {
            JsonNode response = client.get()
                    .uri(baseUrl + "/object_info/LoraLoader")
                    .retrieve().body(JsonNode.class);
            List<String> loras = new ArrayList<>();
            if (response == null) {
                return loras;
            }
            JsonNode loraNames = response.path("LoraLoader")
                    .path("input").path("required").path("lora_name").path(0);
            for (JsonNode name : loraNames) {
                loras.add(name.asText());
            }
            return loras;
        } catch (ResourceAccessException e) {
            throw unreachable(baseUrl, e);
        } catch (RestClientResponseException e) {
            return new ArrayList<>();
        }
    }

    /**
     * 接続失敗(名前解決不可・接続拒否・タイムアウト)を、到達したうえでのHTTPエラー
     * ({@link RestClientResponseException})と区別して{@link AiServiceException}(502)にする(issue #1126)。
     * これを通らない{@code ResourceAccessException}は本文の空な409になり、理由も接続先も読めなかった。
     */
    private AiServiceException unreachable(String baseUrl, ResourceAccessException e) {
        String message = "ComfyUIへ到達できません(接続先: " + baseUrl + "): " + e.getMessage();
        log.warn(message, e);
        return new AiServiceException(message, e);
    }

    private ObjectNode buildWorkflow(ComfyUiGenerationParams params) {
        var factory = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        ObjectNode graph = factory.objectNode();

        String checkpoint = params.checkpoint() != null ? params.checkpoint() : this.checkpointName;

        ObjectNode checkpointLoader = factory.objectNode();
        checkpointLoader.put("class_type", "CheckpointLoaderSimple");
        checkpointLoader.putObject("inputs").put("ckpt_name", checkpoint);
        graph.set("4", checkpointLoader);

        ObjectNode latentImage = factory.objectNode();
        latentImage.put("class_type", "EmptyLatentImage");
        ObjectNode latentInputs = latentImage.putObject("inputs");
        latentInputs.put("width", params.width());
        latentInputs.put("height", params.height());
        latentInputs.put("batch_size", params.batchSize());
        graph.set("5", latentImage);

        String clipRef = "4";
        String modelRef = "4";
        if (params.loraName() != null && !params.loraName().isBlank()) {
            ObjectNode loraLoader = factory.objectNode();
            loraLoader.put("class_type", "LoraLoader");
            ObjectNode loraInputs = loraLoader.putObject("inputs");
            loraInputs.put("lora_name", params.loraName());
            double loraWeight = params.loraWeight() != null ? params.loraWeight() : 1.0;
            loraInputs.put("strength_model", loraWeight);
            loraInputs.put("strength_clip", loraWeight);
            loraInputs.putArray("model").add("4").add(0);
            loraInputs.putArray("clip").add("4").add(1);
            graph.set("10", loraLoader);

            modelRef = "10";
            clipRef = "10";
        }

        ObjectNode positive = factory.objectNode();
        positive.put("class_type", "CLIPTextEncode");
        ObjectNode positiveInputs = positive.putObject("inputs");
        positiveInputs.put("text", params.prompt());
        positiveInputs.putArray("clip").add(clipRef).add(1);
        graph.set("6", positive);

        ObjectNode negative = factory.objectNode();
        negative.put("class_type", "CLIPTextEncode");
        ObjectNode negativeInputs = negative.putObject("inputs");
        negativeInputs.put("text", params.negativePrompt());
        negativeInputs.putArray("clip").add(clipRef).add(1);
        graph.set("7", negative);

        ObjectNode sampler = factory.objectNode();
        sampler.put("class_type", "KSampler");
        ObjectNode samplerInputs = sampler.putObject("inputs");
        samplerInputs.put("seed", params.seed());
        samplerInputs.put("steps", params.steps());
        samplerInputs.put("cfg", params.cfgScale());
        samplerInputs.put("sampler_name", params.samplerName());
        samplerInputs.put("scheduler", params.scheduler());
        samplerInputs.put("denoise", 1.0);
        samplerInputs.putArray("model").add(modelRef).add(0);
        samplerInputs.putArray("positive").add("6").add(0);
        samplerInputs.putArray("negative").add("7").add(0);
        samplerInputs.putArray("latent_image").add("5").add(0);
        graph.set("3", sampler);

        ObjectNode vaeDecode = factory.objectNode();
        vaeDecode.put("class_type", "VAEDecode");
        ObjectNode vaeInputs = vaeDecode.putObject("inputs");
        vaeInputs.putArray("samples").add("3").add(0);
        vaeInputs.putArray("vae").add("4").add(2);
        graph.set("8", vaeDecode);

        ObjectNode saveImage = factory.objectNode();
        saveImage.put("class_type", "SaveImage");
        ObjectNode saveInputs = saveImage.putObject("inputs");
        saveInputs.put("filename_prefix", "letsblog");
        saveInputs.putArray("images").add("8").add(0);
        graph.set("9", saveImage);

        return graph;
    }

    private void clearMemory(String baseUrl) {
        try {
            log.debug("Clearing ComfyUI VRAM memory after generation completion");
            ObjectNode body = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            client.post()
                    .uri(baseUrl + "/api/interrupt")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("ComfyUI VRAM memory cleared successfully");
        } catch (Exception e) {
            log.warn("Failed to clear ComfyUI VRAM memory: {}", e.getMessage());
        }
    }
}
