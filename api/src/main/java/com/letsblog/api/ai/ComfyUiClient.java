package com.letsblog.api.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.letsblog.api.config.LegacyJacksonRestClientConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ComfyUIのAPI(/prompt, /history, /view, /object_info)を呼び出し、txt2img画像を生成するクライアント。
 * ComfyUIは非同期のキュー方式のため、/prompt投入後 /history をポーリングして完了を待つ。
 * 処理完了後はVRAMをクリアし、メモリリークを防止する。
 */
@Component
@Slf4j
public class ComfyUiClient {

    private static final int POLL_INTERVAL_MS = 1000;
    private static final int MAX_POLL_ATTEMPTS = 120;

    private final RestClient client;
    private final String checkpointName;

    @Autowired
    public ComfyUiClient(@Value("${app.comfyui-base-url}") String baseUrl,
                          @Value("${app.comfyui-checkpoint}") String checkpointName) {
        this(RestClient.builder().baseUrl(baseUrl), checkpointName);
    }

    /** テスト専用: MockRestServiceServerを介せるようRestClient.Builderを直接受け取るコンストラクタ。 */
    ComfyUiClient(RestClient.Builder builder, String checkpointName) {
        LegacyJacksonRestClientConfig.preferJackson2(builder);
        this.client = builder.build();
        this.checkpointName = checkpointName;
    }

    /**
     * batch_size枚分の画像を生成する。EmptyLatentImageのbatch_sizeに応じてComfyUI側のSaveImageノードが
     * 複数ファイルを出力するため、historyのoutputs.images配列を全件取得して1枚ずつ/viewで取得する。
     * 処理完了後にVRAMをクリアする。
     */
    public List<ComfyUiImage> generateImage(ComfyUiGenerationParams params) {
        String clientId = UUID.randomUUID().toString();
        ObjectNode workflow = buildWorkflow(params);

        ObjectNode requestBody = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        requestBody.set("prompt", workflow);
        requestBody.put("client_id", clientId);

        String promptId;
        try {
            JsonNode submitResponse = client.post()
                    .uri("/prompt")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(JsonNode.class);
            promptId = submitResponse.get("prompt_id").asText();
        } catch (RestClientResponseException e) {
            throw new AiServiceException("ComfyUIへのジョブ投入に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }

        List<JsonNode> outputImages = pollForResult(promptId);
        List<ComfyUiImage> images = new ArrayList<>();
        for (JsonNode outputImage : outputImages) {
            String filename = outputImage.get("filename").asText();
            String subfolder = outputImage.has("subfolder") ? outputImage.get("subfolder").asText() : "";
            String type = outputImage.has("type") ? outputImage.get("type").asText() : "output";

            byte[] data = client.get()
                    .uri(uriBuilder -> uriBuilder.path("/view")
                            .queryParam("filename", filename)
                            .queryParam("subfolder", subfolder)
                            .queryParam("type", type)
                            .build())
                    .retrieve()
                    .body(byte[].class);

            images.add(new ComfyUiImage(filename, data, "image/png"));
        }

        clearMemory();

        return images;
    }

    private List<JsonNode> pollForResult(String promptId) {
        for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            JsonNode history = client.get().uri("/history/" + promptId).retrieve().body(JsonNode.class);
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
                Thread.sleep(POLL_INTERVAL_MS);
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
        try {
            JsonNode response = client.get().uri("/object_info/CheckpointLoaderSimple").retrieve().body(JsonNode.class);
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
        } catch (RestClientResponseException e) {
            throw new AiServiceException("ComfyUIチェックポイント一覧の取得に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
    }

    /**
     * ComfyUIが対応しているサンプラー名の一覧を取得する(GET /object_info/KSampler)。
     */
    public List<String> listSamplers() {
        try {
            JsonNode response = client.get().uri("/object_info/KSampler").retrieve().body(JsonNode.class);
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
        } catch (RestClientResponseException e) {
            throw new AiServiceException("ComfyUIサンプラー一覧の取得に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
    }

    /**
     * ComfyUIが対応しているスケジューラー名の一覧を取得する(GET /object_info/KSampler)。
     */
    public List<String> listSchedulers() {
        try {
            JsonNode response = client.get().uri("/object_info/KSampler").retrieve().body(JsonNode.class);
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
        } catch (RestClientResponseException e) {
            throw new AiServiceException("ComfyUIスケジューラー一覧の取得に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
    }

    /**
     * ComfyUIに配置されているLoRAモデルの一覧を取得する(GET /object_info/LoraLoader)。
     * LoraLoaderノードが未実装のComfyUI環境では例外を投げず空リストを返す。
     */
    public List<String> listLoras() {
        try {
            JsonNode response = client.get().uri("/object_info/LoraLoader").retrieve().body(JsonNode.class);
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
        } catch (RestClientResponseException e) {
            return new ArrayList<>();
        }
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
        long seed = params.seed() != null && params.seed() >= 0
                ? params.seed() : (System.nanoTime() & 0xFFFFFFFFL);
        samplerInputs.put("seed", seed);
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

    private void clearMemory() {
        try {
            log.debug("Clearing ComfyUI VRAM memory after generation completion");
            ObjectNode body = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            client.post()
                    .uri("/api/interrupt")
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
