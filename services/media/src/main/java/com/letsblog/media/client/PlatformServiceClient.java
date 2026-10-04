package com.letsblog.media.client;

import com.letsblog.common.auth.ServiceTokenClient;
import com.letsblog.common.auth.ServiceTokenUnavailableException;
import com.letsblog.common.client.ServiceAuthHeaders;
import com.letsblog.common.net.ConnectionDestinationGuard;
import com.letsblog.common.net.GuardedTarget;
import com.letsblog.media.ai.ImageGenerationConfigProvider;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 画像生成の接続設定(ComfyUIのbaseUrl・ChatGPTのbaseUrl)をplatform-serviceから取得する
 * 内部ブリッジ。{@code system_settings}の所有権はplatform-service(issue #693)にある。
 *
 * <p>issue #583で画像生成本体がmedia-serviceへ移ったのに伴い、legacy-apiの同名クラスのうち
 * <b>{@link ImageGenerationConfigProvider}の実装部分だけ</b>を移設した。
 * Brave Search APIキー・LLM接続設定はmedia-serviceでは使わないため持ち込んでいない。
 *
 * <p><b>認証</b>(issue #742): 呼び出し先はシステム全体で1つの値を解決するだけで特定ユーザーの
 * データではないため、呼び出し元ユーザーのBearerトークンは転送せず、{@code letsblog-services}
 * クライアントのClient Credentials Grant({@link ServiceTokenClient}、#567)で
 * このサービス自身の身元を示すトークンを付与する。
 */
@Component
public class PlatformServiceClient implements ImageGenerationConfigProvider {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    /**
     * ComfyUiClient/ChatGptImageClientは1回の画像生成処理の中で
     * {@code comfyUiBaseUrl()}を複数回
     * (ComfyUiClientは最大4回)独立に呼び出す。都度platform-serviceへHTTP往復すると
     * 画像生成という既に低速な処理をさらに遅くするため、短いTTLで使い回す。
     */
    private static final Duration CONFIG_CACHE_TTL = Duration.ofSeconds(5);

    private final RestClient restClient;
    private final ServiceTokenClient serviceTokenClient;
    private final AiServiceConnectionClient aiServiceConnectionClient;
    private volatile CachedImageGenerationConfig cachedImageGenerationConfig;

    private final ConnectionDestinationGuard destinationGuard;
    private final String chatGptBaseUrl;

    /** ChatGPT画像生成の接続先。設定項目は持たず常にここへ向く(issue #1569)。 */
    static final String OPENAI_BASE_URL = "https://api.openai.com/v1";

    /** 受け入れテスト環境(docker-compose.e2e-stubs.yml)だけが有効にするプロファイル。 */
    static final String E2E_STUBS_PROFILE = "e2e-stubs";

    /** 受け入れテスト環境の画像生成スタブ(infra/e2e-stubs)。 */
    static final String E2E_STUB_CHATGPT_BASE_URL = "http://image-stub:8080";

    /**
     * ChatGPT画像生成の接続先は本番では常に{@link #OPENAI_BASE_URL}。
     * {@value #E2E_STUBS_PROFILE}プロファイルが有効なときだけ(受け入れテスト環境)スタブへ向ける(issue #1569)。
     */
    @Autowired
    public PlatformServiceClient(
            RestClient.Builder builder,
            @Value("${app.platform-service-uri}") String platformServiceUri,
            ServiceTokenClient serviceTokenClient,
            AiServiceConnectionClient aiServiceConnectionClient,
            Environment environment) {
        this(builder, platformServiceUri, serviceTokenClient, aiServiceConnectionClient,
                ConnectionDestinationGuard.system(),
                environment.acceptsProfiles(Profiles.of(E2E_STUBS_PROFILE))
                        ? E2E_STUB_CHATGPT_BASE_URL : OPENAI_BASE_URL);
    }

    /** テスト専用: 本番と同じ接続先(OpenAI)で作る。 */
    PlatformServiceClient(
            RestClient.Builder builder, String platformServiceUri, ServiceTokenClient serviceTokenClient,
            AiServiceConnectionClient aiServiceConnectionClient) {
        this(builder, platformServiceUri, serviceTokenClient, aiServiceConnectionClient,
                ConnectionDestinationGuard.system(), OPENAI_BASE_URL);
    }

    /** テスト専用: 名前解決とインタフェース列挙を差し替えた検査を注入する(issue #1547)。 */
    PlatformServiceClient(
            RestClient.Builder builder, String platformServiceUri, ServiceTokenClient serviceTokenClient,
            AiServiceConnectionClient aiServiceConnectionClient, ConnectionDestinationGuard destinationGuard) {
        this(builder, platformServiceUri, serviceTokenClient, aiServiceConnectionClient, destinationGuard,
                OPENAI_BASE_URL);
    }

    private PlatformServiceClient(
            RestClient.Builder builder, String platformServiceUri, ServiceTokenClient serviceTokenClient,
            AiServiceConnectionClient aiServiceConnectionClient, ConnectionDestinationGuard destinationGuard,
            String chatGptBaseUrl) {
        this.chatGptBaseUrl = chatGptBaseUrl;
        this.destinationGuard = destinationGuard;
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.clone().baseUrl(platformServiceUri).requestFactory(requestFactory).build();
        this.serviceTokenClient = serviceTokenClient;
        this.aiServiceConnectionClient = aiServiceConnectionClient;
    }

    private record ImageGenerationConfigResponse(String comfyUiBaseUrl) {
    }

    private record CachedImageGenerationConfig(ImageGenerationConfigResponse value, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    /**
     * プロジェクトの上書き(ai-service所有、issue #1503)があればそれ、無ければ(または{@code projectId}が
     * nullなら)システム設定の値。
     */
    @Override
    public String comfyUiBaseUrl(Long projectId) {
        String override = projectId == null ? null : aiServiceConnectionClient.comfyUiBaseUrlOverride(projectId);
        return override != null ? override : imageGenerationConfig().comfyUiBaseUrl();
    }

    /**
     * プロジェクトの上書きは接続時に解決後のアドレスを検査して固定したURLを返す(issue #1547)。
     * システム設定の既定の接続先は検査しない。
     */
    @Override
    public GuardedTarget comfyUiTarget(Long projectId) {
        String override = projectId == null ? null : aiServiceConnectionClient.comfyUiBaseUrlOverride(projectId);
        if (override == null) {
            return new GuardedTarget(imageGenerationConfig().comfyUiBaseUrl(), null);
        }
        return destinationGuard.check("ComfyUI", override);
    }

    /** プロジェクトのキー(ai-service所有、issue #1521)だけ。無い・projectId未指定ならnullで、システム設定へは落とさない。 */
    @Override
    public String chatGptApiKey(Long projectId) {
        return projectId == null ? null : aiServiceConnectionClient.openAiApiKey(projectId);
    }

    @Override
    public String chatGptBaseUrl() {
        return chatGptBaseUrl;
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
            cachedImageGenerationConfig = new CachedImageGenerationConfig(result, Instant.now().plus(CONFIG_CACHE_TTL));
            return result;
        } catch (RestClientException | ServiceTokenUnavailableException e) {
            throw new IllegalStateException(
                    "platform-serviceの画像生成設定取得呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * このサービス自身のアクセストークンをAuthorizationヘッダーに載せる(issue #742)。
     *
     * <p>この{@code Consumer}は{@code headers(...)}呼び出し時点で<b>即時評価される</b>
     * ({@code retrieve()}まで遅延しない)。Keycloak停止等でトークンを取得できないと
     * {@link ServiceTokenUnavailableException}が投げられるが、これは{@code RestClientException}
     * ではないため、上のcatch節で明示的に捕まえて{@code IllegalStateException}へ包み直している。
     */
    private Consumer<HttpHeaders> serviceAuth() {
        return ServiceAuthHeaders.clientCredentials(serviceTokenClient);
    }
}
