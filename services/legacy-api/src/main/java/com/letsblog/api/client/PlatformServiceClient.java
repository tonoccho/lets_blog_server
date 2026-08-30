package com.letsblog.api.client;

import com.letsblog.api.ai.AiProvider;
import com.letsblog.api.ai.ImageGenerationConfigProvider;
import com.letsblog.common.auth.ServiceTokenClient;
import com.letsblog.common.client.ServiceAuthHeaders;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * システム全体のグローバル設定(system_settings: Brave Search APIキー・実効LLM接続設定・画像生成
 * 接続設定)の所有権がplatform-serviceへ移った(issue #693)ことに伴う内部ブリッジ。
 * SystemSettingService/AppSettingServiceはlegacy-apiからplatform-serviceへ移設されたため、
 * legacy-apiに残る以下の呼び出し元は本クライアント経由でplatform-serviceへ問い合わせる。
 * <ul>
 *   <li>{@link com.letsblog.api.controller.AiBridgeController}(ai-service向けブリッジ、
 *       {@code /api/internal/ai/system-settings/brave-search-api-key}・
 *       {@code /api/internal/ai/llm-config})</li>
 *   <li>{@link com.letsblog.api.ai.ChatGptImageClient}/{@link com.letsblog.api.ai.ComfyUiClient}
 *       (画像生成、issue #574で移設されずlegacy-apiに残置。本クラスが
 *       {@link ImageGenerationConfigProvider}を実装することで、コンストラクタ注入先を差し替える
 *       だけで済むようにする)</li>
 * </ul>
 *
 * <p>ダッシュボードの接続サービス状態チェック(旧ConnectedServiceStatusService)は、issue #695
 * (C10-3)でplatform-serviceへ移設され、Brave Search APIキーの設定有無もSystemSettingServiceへの
 * 同一プロセス内呼び出しに置き換わったため、本クラス経由のブリッジは不要になった。
 *
 * <p>content-service(#576)のContentServiceClientと同じブリッジパターンを踏襲する。
 *
 * <p><b>認証</b>(issue #742): 呼び出し先はいずれもシステム全体で1つの値を解決するだけで、
 * 特定ユーザーのデータではないため、呼び出し元ユーザーのBearerトークンを転送する
 * ({@code ServiceAuthHeaders#forwardedBearer})必要はない。代わりに
 * {@code letsblog-services}クライアントのClient Credentials Grant
 * ({@link ServiceTokenClient}、#567)でこのサービス自身の身元を示すトークンを付与する。
 * 画像生成のようにHTTPリクエストのスコープ外(非同期処理)から呼ばれる経路もあるため、
 * 呼び出し元トークンに依存しないこの方式が適している。
 *
 * <p>#742以前はAuthorizationヘッダーを一切付与しておらず、そのためplatform-service側は
 * {@code /api/internal/platform/**}をpermitAllのまま据え置くしかなかった。しかしこれらの
 * エンドポイントはBrave Search APIキー・LLM APIキー・ChatGPTキーという実際のシークレットを返す。
 * gatewayのルート表に載っていないため外部からは到達できないものの、内部ネットワークからは
 * 無防備だった。project-service/publishing-serviceの内部ブリッジは既にJWT必須なので、
 * platform-serviceだけが一貫性を欠いていた。
 */
@Component
public class PlatformServiceClient implements ImageGenerationConfigProvider {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final ServiceTokenClient serviceTokenClient;

    public PlatformServiceClient(
            RestClient.Builder builder,
            @Value("${app.platform-service-uri}") String platformServiceUri,
            ServiceTokenClient serviceTokenClient) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.clone().baseUrl(platformServiceUri).requestFactory(requestFactory).build();
        this.serviceTokenClient = serviceTokenClient;
    }

    /**
     * このサービス自身のアクセストークンをAuthorizationヘッダーに載せる(issue #742)。
     * {@link ServiceTokenClient}はトークンを有効期限までキャッシュするため、
     * 呼び出しごとにKeycloakへ往復するわけではない。
     */
    private Consumer<HttpHeaders> serviceAuth() {
        return ServiceAuthHeaders.clientCredentials(serviceTokenClient);
    }

    private record SystemBraveSearchApiKeyResponse(String apiKey) {
    }

    /** AiBridgeController#systemBraveSearchApiKeyが使う。未設定ならnull。 */
    public String getBraveSearchApiKey() {
        try {
            SystemBraveSearchApiKeyResponse result = restClient.get()
                    .uri("/api/internal/platform/system-settings/brave-search-api-key")
                    .headers(serviceAuth())
                    .retrieve()
                    .body(SystemBraveSearchApiKeyResponse.class);
            return result == null ? null : result.apiKey();
        } catch (RestClientException e) {
            throw new IllegalStateException("platform-serviceのBrave Search APIキー取得呼び出しに失敗しました: "
                    + e.getMessage(), e);
        }
    }

    public record LlmConfigResponse(
            String provider, String baseUrl, String apiKey, String defaultModel,
            List<String> availableModels, long requestTimeoutSeconds) {
    }

    /** AiBridgeController#llmConfigが使う。providerを指定しなければシステム設定の既定プロバイダーを使う。 */
    public LlmConfigResponse llmConfig(AiProvider provider) {
        try {
            LlmConfigResponse result = restClient.get()
                    .uri(uriBuilder -> {
                        var builder = uriBuilder.path("/api/internal/platform/llm-config");
                        if (provider != null) {
                            builder.queryParam("provider", provider.name());
                        }
                        return builder.build();
                    })
                    .headers(serviceAuth())
                    .retrieve()
                    .body(LlmConfigResponse.class);
            if (result == null) {
                throw new IllegalStateException("platform-serviceから空の応答を受け取りました");
            }
            return result;
        } catch (RestClientException e) {
            throw new IllegalStateException("platform-serviceのLLM接続設定取得呼び出しに失敗しました: "
                    + e.getMessage(), e);
        }
    }

    private record ImageGenerationConfigResponse(
            String comfyUiBaseUrl, String chatGptApiKey, String chatGptBaseUrl) {
    }

    // ComfyUiClient/ChatGptImageClientは1回の画像生成処理の中でcomfyUiBaseUrl()/chatGptApiKey()/
    // chatGptBaseUrl()を複数回(ComfyUiClientは最大4回)独立に呼び出す。都度platform-serviceへHTTP
    // 往復すると画像生成という既に低速な処理をさらに遅くするため、短いTTL(ServiceTokenClientの
    // キャッシュと同じ発想。ただしこちらは認証情報ではなく設定値のキャッシュ)で使い回す。
    private static final Duration CONFIG_CACHE_TTL = Duration.ofSeconds(5);
    private volatile CachedImageGenerationConfig cachedImageGenerationConfig;

    private record CachedImageGenerationConfig(ImageGenerationConfigResponse value, java.time.Instant expiresAt) {
        boolean isExpired() {
            return java.time.Instant.now().isAfter(expiresAt);
        }
    }

    private synchronized ImageGenerationConfigResponse imageGenerationConfig() {
        CachedImageGenerationConfig cached = cachedImageGenerationConfig;
        if (cached != null && !cached.isExpired()) {
            return cached.value();
        }
        try {
            ImageGenerationConfigResponse result = restClient.get()
                    .uri("/api/internal/platform/image-generation-config")
                    .headers(serviceAuth())
                    .retrieve()
                    .body(ImageGenerationConfigResponse.class);
            if (result == null) {
                throw new IllegalStateException("platform-serviceから空の応答を受け取りました");
            }
            cachedImageGenerationConfig =
                    new CachedImageGenerationConfig(result, java.time.Instant.now().plus(CONFIG_CACHE_TTL));
            return result;
        } catch (RestClientException e) {
            throw new IllegalStateException("platform-serviceの画像生成設定取得呼び出しに失敗しました: "
                    + e.getMessage(), e);
        }
    }

    @Override
    public String comfyUiBaseUrl() {
        return imageGenerationConfig().comfyUiBaseUrl();
    }

    @Override
    public String chatGptApiKey() {
        return imageGenerationConfig().chatGptApiKey();
    }

    @Override
    public String chatGptBaseUrl() {
        return imageGenerationConfig().chatGptBaseUrl();
    }
}
