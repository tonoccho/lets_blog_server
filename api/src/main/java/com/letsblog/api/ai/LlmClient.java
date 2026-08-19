package com.letsblog.api.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.letsblog.api.config.LegacyJacksonRestClientConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * OpenAI互換のChat Completions API(POST {baseUrl}/chat/completions)を呼び出す薄いクライアント。
 * OpenAI/Groq/OpenRouter等、同APIを提供する外部LLMサービスであればbaseUrlの変更のみで切り替えられる
 * (issue #375の調査に基づきissue #376でOllamaから置き換え)。
 * 接続設定(baseUrl/APIキー/既定モデル/タイムアウト)はLlmConfigProviderから呼び出しの都度取得する
 * (issue #403でWeb管理画面のシステム設定から変更可能になったため、構築時に固定値として保持しない)。
 */
@Component
public class LlmClient {

    /**
     * 一部のプロバイダ経由で推論(thinking)系モデルを利用した場合に出力へ混入しうる
     * &lt;think&gt;...&lt;/think&gt;ブロックを除去する。混入したままだと呼び出し元(ArticlePlanService等)の
     * JSON抽出が不安定に壊れることがあるため、全呼び出し元に共通の対策としてここで一括して取り除く。
     */
    private static final Pattern THINK_BLOCK_PATTERN = Pattern.compile("(?s)<think>.*?</think>");

    private final LlmConfigProvider configProvider;

    @Autowired
    public LlmClient(LlmConfigProvider configProvider) {
        this.configProvider = configProvider;
    }

    /** テスト専用: stripThinkingBlocksのロジックのみを固定値で検証するためのコンストラクタ。 */
    LlmClient(String baseUrl, String apiKey, String model, long requestTimeoutSeconds) {
        this.configProvider = new LlmConfigProvider() {
            @Override
            public String baseUrl() {
                return baseUrl;
            }

            @Override
            public String apiKey() {
                return apiKey;
            }

            @Override
            public String defaultModel() {
                return model;
            }

            @Override
            public long requestTimeoutSeconds() {
                return requestTimeoutSeconds;
            }
        };
    }

    public String generate(String prompt) {
        return generate(prompt, configProvider.defaultModel());
    }

    public String generate(String prompt, String modelName) {
        try {
            ArrayNode messages = JsonNodeFactory.instance.arrayNode();
            messages.add(JsonNodeFactory.instance.objectNode().put("role", "user").put("content", prompt));
            JsonNode body = JsonNodeFactory.instance.objectNode()
                    .put("model", modelName)
                    .put("stream", false)
                    .set("messages", messages);

            JsonNode response = buildClient().post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            String content = response.get("choices").get(0).get("message").get("content").asText();
            return stripThinkingBlocks(content).trim();
        } catch (RestClientResponseException e) {
            throw new AiServiceException("LLM呼び出しに失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new AiServiceException("LLM呼び出し中にエラーが発生しました（タイムアウトまたはネットワークエラーの可能性があります）: " + e.getMessage(), e);
        }
    }

    private RestClient buildClient() {
        Duration requestTimeout = Duration.ofSeconds(configProvider.requestTimeoutSeconds());
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(requestTimeout).build());
        requestFactory.setReadTimeout(requestTimeout);
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(configProvider.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + configProvider.apiKey());
        LegacyJacksonRestClientConfig.preferJackson2(builder);
        return builder.build();
    }

    /** package-privateはテストから直接検証するため(HTTP呼び出しをモックせずロジックだけ確認できるように)。 */
    String stripThinkingBlocks(String text) {
        return THINK_BLOCK_PATTERN.matcher(text).replaceAll("").trim();
    }
}
