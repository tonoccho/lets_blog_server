package com.letsblog.media.client;

import com.letsblog.common.auth.ServiceTokenClient;
import com.letsblog.common.auth.ServiceTokenUnavailableException;
import com.letsblog.common.client.ServiceAuthHeaders;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * ai-serviceが持つプロジェクト単位の接続先上書き(issue #1503、{@code project_ai_settings}はai-service所有、
 * ADR-0004)を、内部ブリッジ{@code /api/internal/ai/projects/{projectId}/connections}から取得する。
 *
 * <p>認証は{@link PlatformServiceClient}と同じくサービス自身のClient Credentialsトークン。画像生成は
 * {@code @Async}のジョブスレッド(#1405)からも呼ばれ、ユーザーのBearerに頼れないため。
 *
 * <p>ComfyUiClientは1回の生成処理で接続先を複数回引くため、プロジェクト別に5秒だけ結果を使い回す
 * ({@link PlatformServiceClient}の画像生成設定キャッシュと同じTTL)。
 */
@Component
public class AiServiceConnectionClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CACHE_TTL = Duration.ofSeconds(5);

    private record ConnectionsResponse(String ollamaBaseUrl, String comfyuiBaseUrl) {
    }

    private record Cached(String comfyuiBaseUrl, Instant expiresAt) {
    }

    private final RestClient restClient;
    private final ServiceTokenClient serviceTokenClient;
    private final Clock clock;
    private final Map<Long, Cached> cache = new HashMap<>();

    @Autowired
    public AiServiceConnectionClient(
            RestClient.Builder builder, @Value("${app.ai-service-uri}") String aiServiceUri,
            ServiceTokenClient serviceTokenClient) {
        this(builder, aiServiceUri, serviceTokenClient, Clock.systemUTC());
    }

    /** テスト専用: キャッシュ期限の検証のため時計を差し替える。 */
    AiServiceConnectionClient(
            RestClient.Builder builder, String aiServiceUri, ServiceTokenClient serviceTokenClient, Clock clock) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.clone().baseUrl(aiServiceUri).requestFactory(requestFactory).build();
        this.serviceTokenClient = serviceTokenClient;
        this.clock = clock;
    }

    /**
     * プロジェクトのComfyUI接続先の上書き。上書きが無い(null/空)ときはnull。
     * 取得に失敗したときは例外にする。黙ってシステム設定へ落とすと、上書きしたプロジェクトの生成が
     * 意図しない接続先へ向かってしまうため。
     */
    public synchronized String comfyUiBaseUrlOverride(Long projectId) {
        Cached cached = cache.get(projectId);
        if (cached != null && clock.instant().isBefore(cached.expiresAt())) {
            return cached.comfyuiBaseUrl();
        }
        try {
            ConnectionsResponse response = restClient.get()
                    .uri("/api/internal/ai/projects/{projectId}/connections", projectId)
                    .headers(ServiceAuthHeaders.clientCredentials(serviceTokenClient))
                    .retrieve()
                    .body(ConnectionsResponse.class);
            String override = response == null || response.comfyuiBaseUrl() == null
                    || response.comfyuiBaseUrl().isBlank() ? null : response.comfyuiBaseUrl();
            cache.put(projectId, new Cached(override, clock.instant().plus(CACHE_TTL)));
            return override;
        } catch (RestClientException | ServiceTokenUnavailableException e) {
            throw new IllegalStateException(
                    "ai-serviceのプロジェクト接続先取得呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    private record OpenAiApiKeyResponse(String apiKey) {
        @Override
        public String toString() {
            return "OpenAiApiKeyResponse[apiKey=****]";
        }
    }

    /**
     * プロジェクトのChatGPT(OpenAI) APIキー(issue #1521)。未設定(null/空白)ならnull。
     * キーはキャッシュせず毎回取得する(秘密値をメモリに留めず、変更・解除の直後から反映させるため。
     * 呼び出しは1回の生成リクエストにつき1回)。値はログ・例外メッセージに含めない。
     */
    public String openAiApiKey(Long projectId) {
        try {
            OpenAiApiKeyResponse response = restClient.get()
                    .uri("/api/internal/ai/projects/{projectId}/openai-api-key", projectId)
                    .headers(ServiceAuthHeaders.clientCredentials(serviceTokenClient))
                    .retrieve()
                    .body(OpenAiApiKeyResponse.class);
            return response == null || response.apiKey() == null || response.apiKey().isBlank()
                    ? null : response.apiKey();
        } catch (RestClientException | ServiceTokenUnavailableException e) {
            throw new IllegalStateException(
                    "ai-serviceのプロジェクトAPIキー取得呼び出しに失敗しました: " + e.getClass().getSimpleName(), e);
        }
    }
}
