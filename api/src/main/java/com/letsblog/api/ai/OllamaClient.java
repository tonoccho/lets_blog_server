package com.letsblog.api.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * OllamaのREST API(/api/generate, /api/tags, /api/pull, /api/delete)を呼び出す薄いクライアント。
 */
@Component
public class OllamaClient {

    private static final Duration PULL_TIMEOUT = Duration.ofMinutes(30);

    private final RestClient client;
    private final HttpClient rawHttpClient;
    private final String baseUrl;
    private final String model;
    private final ObjectMapper objectMapper;

    public OllamaClient(
            @Value("${app.ollama-base-url}") String baseUrl,
            @Value("${app.ollama-model}") String model,
            ObjectMapper objectMapper) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.rawHttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        this.baseUrl = baseUrl;
        this.model = model;
        this.objectMapper = objectMapper;
    }

    public String generate(String prompt) {
        return generate(prompt, model);
    }

    public String generate(String prompt, String modelName) {
        try {
            JsonNode body = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                    .put("model", modelName)
                    .put("prompt", prompt)
                    .put("stream", false);

            JsonNode response = client.post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            return response.get("response").asText().trim();
        } catch (RestClientResponseException e) {
            throw new AiServiceException("Ollama呼び出しに失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
    }

    /**
     * インストール済みモデルの一覧を取得する(GET /api/tags)。
     */
    public List<OllamaModelInfo> listModels() {
        try {
            JsonNode response = client.get().uri("/api/tags").retrieve().body(JsonNode.class);
            List<OllamaModelInfo> models = new ArrayList<>();
            if (response != null && response.has("models")) {
                for (JsonNode m : response.get("models")) {
                    String name = m.get("name").asText();
                    long size = m.has("size") ? m.get("size").asLong() : 0L;
                    String modifiedAt = m.has("modified_at") ? m.get("modified_at").asText() : null;
                    models.add(new OllamaModelInfo(name, size, modifiedAt));
                }
            }
            return models;
        } catch (RestClientResponseException e) {
            throw new AiServiceException("Ollamaモデル一覧の取得に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
    }

    /**
     * モデルをダウンロードする(POST /api/pull, stream:true)。逐次届く進捗をonProgressへ通知する。
     * 数分かかりうるためブロッキング呼び出しになる。呼び出し元で非同期実行すること。
     */
    public void pullModel(String modelName, Consumer<OllamaPullProgress> onProgress) {
        try {
            JsonNode requestBody = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                    .put("name", modelName)
                    .put("stream", true);
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/pull"))
                    .header("Content-Type", "application/json")
                    .timeout(PULL_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();
            HttpResponse<InputStream> response =
                    rawHttpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                String errorBody;
                try (InputStream errorStream = response.body()) {
                    errorBody = new String(errorStream.readAllBytes(), StandardCharsets.UTF_8);
                }
                throw new AiServiceException(
                        "Ollamaモデルのインストールに失敗しました: HTTP " + response.statusCode() + " " + errorBody, null);
            }
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode node = objectMapper.readTree(line);
                    if (node.has("error")) {
                        throw new AiServiceException("Ollamaモデルのインストールに失敗しました: " + node.get("error").asText(), null);
                    }
                    String status = node.path("status").asText("");
                    long total = node.path("total").asLong(0);
                    long completed = node.path("completed").asLong(0);
                    onProgress.accept(new OllamaPullProgress(status, total, completed));
                }
            }
        } catch (IOException e) {
            throw new AiServiceException("Ollamaモデルのインストール中にエラーが発生しました: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiServiceException("Ollamaモデルのインストールが中断されました", e);
        }
    }

    /**
     * モデルを削除する(POST /api/delete)。
     */
    public void deleteModel(String modelName) {
        try {
            JsonNode body = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                    .put("name", modelName);
            client.method(org.springframework.http.HttpMethod.DELETE)
                    .uri("/api/delete")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new AiServiceException("Ollamaモデルの削除に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
    }
}
