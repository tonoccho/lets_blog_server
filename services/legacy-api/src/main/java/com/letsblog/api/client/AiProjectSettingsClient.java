package com.letsblog.api.client;

import com.letsblog.api.ai.AiServiceException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * ai-serviceの{@code /api/internal/ai/projects/{projectId}/brave-search-api-key}を呼び出す
 * クライアント(issue #574)。
 *
 * <p>ProjectApiKeyController(Web管理画面向け、プロジェクト単位のAPIキー設定)は、GitHubトークン/
 * Google Analytics/AdSense等project-service/analytics-serviceが未抽出のためlegacy-apiに残る設定と
 * まとめてBrave Search APIキーも扱っているが、project_ai_settingsテーブル自体はai-serviceが所有する
 * ため、この部分だけai-serviceへのブリッジ経由にする(media-service(#573)のCmsMediaBridgeControllerと
 * 同じ暫定策。呼び出し元ユーザーのBearerトークンをそのまま転送する)。
 */
@Component
public class AiProjectSettingsClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;
    private final HttpServletRequest request;

    public AiProjectSettingsClient(
            RestClient.Builder builder, @Value("${app.ai-service-uri}") String aiServiceUri,
            HttpServletRequest request) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.baseUrl(aiServiceUri).requestFactory(requestFactory).build();
        this.request = request;
    }

    public boolean isBraveSearchApiKeyConfigured(Long projectId) {
        try {
            StatusResponse response = restClient.get()
                    .uri("/api/internal/ai/projects/{projectId}/brave-search-api-key", projectId)
                    .headers(this::setAuthorization)
                    .retrieve()
                    .body(StatusResponse.class);
            return response != null && response.configured();
        } catch (RestClientException e) {
            throw new AiServiceException("ai-serviceのbrave-search-api-key状態取得に失敗しました: " + e.getMessage(), e);
        }
    }

    public void setBraveSearchApiKey(Long projectId, String apiKey) {
        try {
            restClient.put()
                    .uri("/api/internal/ai/projects/{projectId}/brave-search-api-key", projectId)
                    .headers(this::setAuthorization)
                    .body(Map.of("apiKey", apiKey))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new AiServiceException("ai-serviceのbrave-search-api-key設定に失敗しました: " + e.getMessage(), e);
        }
    }

    public void clearBraveSearchApiKey(Long projectId) {
        try {
            restClient.delete()
                    .uri("/api/internal/ai/projects/{projectId}/brave-search-api-key", projectId)
                    .headers(this::setAuthorization)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new AiServiceException("ai-serviceのbrave-search-api-key削除に失敗しました: " + e.getMessage(), e);
        }
    }

    private record StatusResponse(boolean configured) {
    }

    private void setAuthorization(HttpHeaders headers) {
        String bearerToken = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        }
    }
}
