package com.letsblog.api.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * OllamaのREST API(/api/generate)を呼び出す薄いクライアント。
 */
@Component
public class OllamaClient {

    private final RestClient client;
    private final String model;

    public OllamaClient(@Value("${app.ollama-base-url}") String baseUrl, @Value("${app.ollama-model}") String model) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.model = model;
    }

    public String generate(String prompt) {
        try {
            JsonNode body = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                    .put("model", model)
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
}
