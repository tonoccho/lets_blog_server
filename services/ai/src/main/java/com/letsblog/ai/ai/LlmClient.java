package com.letsblog.ai.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.letsblog.ai.config.LegacyJacksonRestClientConfig;
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
 * OLLAMA/OPENAIはOpenAI互換のChat Completions API(POST {baseUrl}/chat/completions)、
 * CLAUDEはAnthropic Messages API(POST /v1/messages)を呼び出す薄いクライアント
 * (issue #375/#376でOllamaから置き換え、issue #530でプロバイダ切り替えに対応)。
 * 接続設定(baseUrl/APIキー/既定モデル/タイムアウト)はLlmConfigProviderから呼び出しの都度取得する
 * (issue #403でWeb管理画面のシステム設定から変更可能になったため、構築時に固定値として保持しない)。
 */
@Component
public class LlmClient {

    /**
     * Claude(Anthropic)はOllama/OpenAIと異なり自前ホスト型のbaseUrl差し替えに対応していないため、
     * 固定のエンドポイントを使う(issue #530)。
     */
    public static final String ANTHROPIC_BASE_URL = "https://api.anthropic.com";

    private static final String ANTHROPIC_VERSION = "2023-06-01";

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

            @Override
            public AiProvider provider() {
                return AiProvider.OPENAI;
            }

            @Override
            public String apiKeyFor(AiProvider provider) {
                return apiKey;
            }

            @Override
            public String defaultModelFor(AiProvider provider) {
                return model;
            }

            @Override
            public String baseUrlFor(AiProvider provider) {
                return baseUrl;
            }

            @Override
            public java.util.List<String> availableModels() {
                return java.util.List.of(model);
            }
        };
    }

    public String generate(String prompt) {
        return generate(prompt, null, null);
    }

    public String generate(String prompt, String modelName) {
        return generate(prompt, modelName, null);
    }

    /**
     * modelName/providerOverrideはいずれもnullなら「システム設定の既定値を使う」を意味する
     * (issue #530: 呼び出し元がプロジェクト単位/リクエスト単位でプロバイダを上書きできるようにするため)。
     */
    public String generate(String prompt, String modelName, AiProvider providerOverride) {
        AiProvider provider = providerOverride != null ? providerOverride : configProvider.provider();
        String model = (modelName != null && !modelName.isBlank())
                ? modelName : configProvider.defaultModelFor(provider);
        String apiKey = configProvider.apiKeyFor(provider);
        if (provider != AiProvider.OLLAMA && (apiKey == null || apiKey.isBlank())) {
            throw new AiServiceException(
                    provider + "のAPIキーが設定されていません。Web管理画面のシステム設定で設定してください。", null);
        }

        if (provider == AiProvider.CLAUDE) {
            return generateWithClaude(prompt, model, apiKey);
        }
        return generateWithOpenAiCompatible(prompt, model, apiKey, configProvider.baseUrlFor(provider));
    }

    private String generateWithOpenAiCompatible(String prompt, String modelName, String apiKey, String baseUrl) {
        try {
            ArrayNode messages = JsonNodeFactory.instance.arrayNode();
            messages.add(JsonNodeFactory.instance.objectNode().put("role", "user").put("content", prompt));
            JsonNode body = JsonNodeFactory.instance.objectNode()
                    .put("model", modelName)
                    .put("stream", false)
                    .set("messages", messages);

            JsonNode response = buildClient(baseUrl, apiKey).post()
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

    /** Anthropic Messages API(POST /v1/messages)を呼び出す。認証ヘッダ/リクエスト形式がOpenAI互換APIと異なる。 */
    private String generateWithClaude(String prompt, String modelName, String apiKey) {
        try {
            ArrayNode messages = JsonNodeFactory.instance.arrayNode();
            messages.add(JsonNodeFactory.instance.objectNode().put("role", "user").put("content", prompt));
            JsonNode body = JsonNodeFactory.instance.objectNode()
                    .put("model", modelName)
                    .put("max_tokens", 4096)
                    .set("messages", messages);

            Duration requestTimeout = Duration.ofSeconds(configProvider.requestTimeoutSeconds());
            JdkClientHttpRequestFactory requestFactory =
                    new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(requestTimeout).build());
            requestFactory.setReadTimeout(requestTimeout);
            RestClient.Builder builder = RestClient.builder()
                    .baseUrl(ANTHROPIC_BASE_URL)
                    .requestFactory(requestFactory)
                    .defaultHeader("x-api-key", apiKey)
                    .defaultHeader("anthropic-version", ANTHROPIC_VERSION);
            LegacyJacksonRestClientConfig.preferJackson2(builder);

            JsonNode response = builder.build().post()
                    .uri("/v1/messages")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            String content = response.get("content").get(0).get("text").asText();
            return stripThinkingBlocks(content).trim();
        } catch (RestClientResponseException e) {
            throw new AiServiceException("LLM呼び出しに失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new AiServiceException("LLM呼び出し中にエラーが発生しました（タイムアウトまたはネットワークエラーの可能性があります）: " + e.getMessage(), e);
        }
    }

    private RestClient buildClient(String baseUrl, String apiKey) {
        Duration requestTimeout = Duration.ofSeconds(configProvider.requestTimeoutSeconds());
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(requestTimeout).build());
        requestFactory.setReadTimeout(requestTimeout);
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + apiKey);
        LegacyJacksonRestClientConfig.preferJackson2(builder);
        return builder.build();
    }

    /** package-privateはテストから直接検証するため(HTTP呼び出しをモックせずロジックだけ確認できるように)。 */
    String stripThinkingBlocks(String text) {
        return THINK_BLOCK_PATTERN.matcher(text).replaceAll("").trim();
    }
}
