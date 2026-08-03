package com.letsblog.api.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;

/**
 * Brave Search API(Web検索)を呼び出す薄いクライアント。壁打ちチャットの応答生成前に
 * 参考情報を取得するために使用する。
 */
@Component
public class BraveSearchClient {

    private final RestClient client;
    private final String apiKey;

    public BraveSearchClient(
            @Value("${app.brave-search-base-url}") String baseUrl,
            @Value("${app.brave-search-api-key}") String apiKey) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.apiKey = apiKey;
    }

    public List<BraveSearchResult> search(String query, int count) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiServiceException("Brave Search APIキーが設定されていません(BRAVE_SEARCH_API_KEY)", null);
        }
        try {
            JsonNode response = client.get()
                    .uri(uriBuilder -> uriBuilder.path("/res/v1/web/search")
                            .queryParam("q", query)
                            .queryParam("count", count)
                            .build())
                    .header("X-Subscription-Token", apiKey)
                    .retrieve()
                    .body(JsonNode.class);

            List<BraveSearchResult> results = new ArrayList<>();
            if (response == null) {
                return results;
            }
            for (JsonNode item : response.path("web").path("results")) {
                results.add(new BraveSearchResult(
                        item.path("title").asText(""),
                        item.path("description").asText(""),
                        item.path("url").asText("")));
            }
            return results;
        } catch (RestClientResponseException e) {
            throw new AiServiceException("Brave Search呼び出しに失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
    }
}
