package com.letsblog.ai.client;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * platform-serviceの内部ブリッジ{@code /api/internal/platform/**}
 * ({@code com.letsblog.platform.controller.InternalPlatformSettingsController})を呼び出すクライアント。
 *
 * <p>issue #583(C14: legacy-apiの解体): システム設定由来の「実効LLM接続設定」と
 * 「システム全体既定のBrave Search APIキー」は、システム設定を所有する platform-service
 * ({@code SystemSettingService}/{@code AppSettingService}、#698 で移設)が持つ。
 * それまで ai-service は legacy-api の {@code AiBridgeController} 経由で問い合わせていたが、
 * <b>legacy-api 側の実装は platform-service への単純な中継</b>
 * ({@code PlatformServiceClient#llmConfig} / {@code #getBraveSearchApiKey} を呼ぶだけ)だった。
 * 経由する意味が無く、legacy-api を解体できない理由の1つになっていたため、直接呼ぶよう切り替えた。
 *
 * <p>認証は他の内部ブリッジ({@link PublishingServiceClient} 等)と同じく<b>呼び出し元の Bearer
 * トークンをそのまま転送</b>する。platform-service の {@code /api/internal/platform/**} は #742 で
 * {@code authenticated()} になっており、有効な JWT であれば通る(legacy-api は Client Credentials の
 * サービストークンを使っていたが、ai-service は利用者のトークンを持っているためそちらで足りる。
 * 新たな資格情報の配布は不要)。
 */
@Component
public class PlatformServiceClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;

    public PlatformServiceClient(
            RestClient.Builder builder, @Value("${app.platform-service-uri}") String platformServiceUri) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.clone().baseUrl(platformServiceUri).requestFactory(requestFactory).build();
    }

    private static void setAuthorization(HttpHeaders headers, String bearerToken) {
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        }
    }

    public record SystemBraveSearchApiKey(String apiKey) {
    }

    /**
     * システム全体既定のBrave Search APIキー(Web管理画面のシステム設定、無ければ環境変数)。
     * {@code WebSearchService}のプロジェクト非依存呼び出し向けのフォールバック。未設定ならnull。
     *
     * <p>取得失敗時に例外を投げずnullを返す方針は移管元(legacy-api経由の実装)と同じ。
     * キーが無ければWeb検索を行わないだけで、記事生成そのものは続行できるため。
     */
    public String resolveSystemBraveSearchApiKey(String bearerToken) {
        try {
            SystemBraveSearchApiKey result = restClient.get()
                    .uri("/api/internal/platform/system-settings/brave-search-api-key")
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .retrieve()
                    .body(SystemBraveSearchApiKey.class);
            return result == null ? null : result.apiKey();
        } catch (RestClientException e) {
            return null;
        }
    }

    public record LlmConfig(
            String provider, String baseUrl, String apiKey, String defaultModel,
            List<String> availableModels, long requestTimeoutSeconds) {
    }

    /**
     * システム設定(Web管理画面、issue #403)で決まる実効LLM接続設定を解決する。
     * providerがnullの場合、platform-service側でシステム設定の既定プロバイダーを解決して使う。
     *
     * <p>こちらは失敗を握り潰さない。LLM接続設定が解決できなければ生成そのものが行えないため、
     * 原因が分かる形で上位へ伝播させる(移管元と同じ方針)。
     */
    public LlmConfig resolveLlmConfig(String provider, String bearerToken) {
        try {
            LlmConfig config = restClient.get()
                    .uri(uriBuilder -> {
                        var builder = uriBuilder.path("/api/internal/platform/llm-config");
                        if (provider != null && !provider.isBlank()) {
                            builder.queryParam("provider", provider);
                        }
                        return builder.build();
                    })
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .retrieve()
                    .body(LlmConfig.class);
            if (config == null) {
                throw new IllegalStateException("platform-serviceから空の応答を受け取りました");
            }
            return config;
        } catch (RestClientResponseException e) {
            String body = e.getResponseBodyAsString();
            throw new IllegalStateException(
                    (body == null || body.isBlank()) ? e.getMessage() : body, e);
        } catch (RestClientException e) {
            throw new IllegalStateException(
                    "platform-serviceのllm-config呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /** 接続先URL(APIキー方式のプロバイダーはnull)・設定の出所・設定有無。APIキーの値は含まれない。 */
    public record ProviderConnectionConfig(String baseUrl, String source, boolean configured) {
    }

    public record AiConnectionsConfig(
            ProviderConnectionConfig ollama, ProviderConnectionConfig comfyui,
            ProviderConnectionConfig openai, ProviderConnectionConfig claude) {
    }

    /**
     * 4プロバイダーの接続先・設定の出所・設定有無を解決する(issue #1499)。
     * 失敗は握り潰さず例外にする。接続先が分からなければ判定そのものが行えないため、
     * {@link #resolveLlmConfig}と同じ方針。
     */
    public AiConnectionsConfig resolveAiConnectionsConfig(String bearerToken) {
        try {
            AiConnectionsConfig config = restClient.get()
                    .uri("/api/internal/platform/ai-connections-config")
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .retrieve()
                    .body(AiConnectionsConfig.class);
            if (config == null) {
                throw new IllegalStateException("platform-serviceから空の応答を受け取りました");
            }
            return config;
        } catch (RestClientResponseException e) {
            String body = e.getResponseBodyAsString();
            throw new IllegalStateException(
                    (body == null || body.isBlank()) ? e.getMessage() : body, e);
        } catch (RestClientException e) {
            throw new IllegalStateException(
                    "platform-serviceのai-connections-config呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }
}
