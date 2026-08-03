package com.letsblog.api.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ComfyUIのAPI(/prompt, /history, /view)を呼び出し、txt2img画像を生成するクライアント。
 * ComfyUIは非同期のキュー方式のため、/prompt投入後 /history をポーリングして完了を待つ。
 */
@Component
public class ComfyUiClient {

    private static final int POLL_INTERVAL_MS = 1000;
    private static final int MAX_POLL_ATTEMPTS = 120;

    private final RestClient client;
    private final String checkpointName;

    public ComfyUiClient(@Value("${app.comfyui-base-url}") String baseUrl,
                          @Value("${app.comfyui-checkpoint}") String checkpointName) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.checkpointName = checkpointName;
    }

    public ComfyUiImage generateImage(String prompt) {
        return generateImage(prompt, this.checkpointName);
    }

    public ComfyUiImage generateImage(String prompt, String checkpoint) {
        String clientId = UUID.randomUUID().toString();
        ObjectNode workflow = buildWorkflow(prompt, checkpoint);

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

        JsonNode outputImage = pollForResult(promptId);
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

        return new ComfyUiImage(filename, data, "image/png");
    }

    private JsonNode pollForResult(String promptId) {
        for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            JsonNode history = client.get().uri("/history/" + promptId).retrieve().body(JsonNode.class);
            JsonNode entry = history != null ? history.get(promptId) : null;

            if (entry != null && entry.has("outputs")) {
                for (JsonNode nodeOutput : entry.get("outputs")) {
                    if (nodeOutput.has("images") && nodeOutput.get("images").size() > 0) {
                        return nodeOutput.get("images").get(0);
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

    private ObjectNode buildWorkflow(String prompt, String checkpoint) {
        var factory = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        ObjectNode graph = factory.objectNode();

        ObjectNode checkpointLoader = factory.objectNode();
        checkpointLoader.put("class_type", "CheckpointLoaderSimple");
        ObjectNode checkpointInputs = checkpointLoader.putObject("inputs");
        checkpointInputs.put("ckpt_name", checkpoint);
        graph.set("4", checkpointLoader);

        ObjectNode latentImage = factory.objectNode();
        latentImage.put("class_type", "EmptyLatentImage");
        ObjectNode latentInputs = latentImage.putObject("inputs");
        latentInputs.put("width", 512);
        latentInputs.put("height", 512);
        latentInputs.put("batch_size", 1);
        graph.set("5", latentImage);

        ObjectNode positive = factory.objectNode();
        positive.put("class_type", "CLIPTextEncode");
        ObjectNode positiveInputs = positive.putObject("inputs");
        positiveInputs.put("text", prompt);
        positiveInputs.putArray("clip").add("4").add(1);
        graph.set("6", positive);

        ObjectNode negative = factory.objectNode();
        negative.put("class_type", "CLIPTextEncode");
        ObjectNode negativeInputs = negative.putObject("inputs");
        negativeInputs.put("text", "low quality, blurry, watermark, text");
        negativeInputs.putArray("clip").add("4").add(1);
        graph.set("7", negative);

        ObjectNode sampler = factory.objectNode();
        sampler.put("class_type", "KSampler");
        ObjectNode samplerInputs = sampler.putObject("inputs");
        samplerInputs.put("seed", System.nanoTime() & 0xFFFFFFFFL);
        samplerInputs.put("steps", 20);
        samplerInputs.put("cfg", 7.0);
        samplerInputs.put("sampler_name", "euler");
        samplerInputs.put("scheduler", "normal");
        samplerInputs.put("denoise", 1.0);
        samplerInputs.putArray("model").add("4").add(0);
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
}
