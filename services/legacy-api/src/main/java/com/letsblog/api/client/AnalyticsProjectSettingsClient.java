package com.letsblog.api.client;

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
import org.springframework.web.client.RestClientResponseException;

/**
 * analytics-serviceの{@code /api/internal/analytics/projects/{projectId}/**}を呼び出すクライアント
 * (issue #578)。
 *
 * <p>ProjectApiKeyController(Web管理画面向け、プロジェクト単位のAPIキー設定)は、GitHubトークン/
 * Brave Search APIキー等project-service/ai-serviceが所有するデータもまとめて扱っているが、
 * analytics_credentialsテーブル自体はanalytics-serviceが所有するため、Google Analytics/AdSense
 * 部分だけanalytics-serviceへのブリッジ経由にする(ai-service(#574)のAiProjectSettingsClientと
 * 同じ暫定策。呼び出し元ユーザーのBearerトークンをそのまま転送する)。
 *
 * <p>入力値検証(サービスアカウントJSON形式等)はanalytics-service側(InternalAnalyticsProjectSettings
 * Controller)で行い、その結果(4xx)はそのままIllegalArgumentException(呼び出し元コントローラーの
 * 既存の@Validと同じ409マッピング)として再送出する。5xx・通信断は{@link AnalyticsServiceException}
 * (502マッピング)として区別する(ai-service(#574)のLegacyApiBridgeClient#resolveGithubAccessと
 * 同じ方針)。
 */
@Component
public class AnalyticsProjectSettingsClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final HttpServletRequest request;

    public AnalyticsProjectSettingsClient(
            RestClient.Builder builder, @Value("${app.analytics-service-uri}") String analyticsServiceUri,
            HttpServletRequest request) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.baseUrl(analyticsServiceUri).requestFactory(requestFactory).build();
        this.request = request;
    }

    // ---- Google Analytics ----

    public record GoogleAnalyticsStatus(boolean configured, String propertyId) {
    }

    public GoogleAnalyticsStatus getGoogleAnalyticsStatus(Long projectId) {
        GoogleAnalyticsStatus status = get(
                "/api/internal/analytics/projects/{projectId}/google-analytics", projectId, GoogleAnalyticsStatus.class);
        return status == null ? new GoogleAnalyticsStatus(false, null) : status;
    }

    public void setGoogleAnalyticsCredentials(Long projectId, String propertyId, String serviceAccountJson) {
        put("/api/internal/analytics/projects/{projectId}/google-analytics", projectId,
                Map.of("propertyId", propertyId, "serviceAccountJson", serviceAccountJson));
    }

    public void clearGoogleAnalyticsCredentials(Long projectId) {
        delete("/api/internal/analytics/projects/{projectId}/google-analytics", projectId);
    }

    // ---- AdSense ----

    public record AdSenseStatus(boolean configured, String accountId, String clientId, boolean hasClientSecret) {
    }

    public AdSenseStatus getAdSenseStatus(Long projectId) {
        AdSenseStatus status =
                get("/api/internal/analytics/projects/{projectId}/adsense", projectId, AdSenseStatus.class);
        return status == null ? new AdSenseStatus(false, null, null, false) : status;
    }

    public void setAdSenseSettings(Long projectId, String accountId, String clientId) {
        put("/api/internal/analytics/projects/{projectId}/adsense", projectId,
                Map.of("accountId", accountId, "clientId", clientId == null ? "" : clientId));
    }

    public void setAdSenseClientSecret(Long projectId, String clientSecret) {
        put("/api/internal/analytics/projects/{projectId}/adsense/client-secret", projectId,
                Map.of("clientSecret", clientSecret));
    }

    public void clearAdSenseCredentials(Long projectId) {
        delete("/api/internal/analytics/projects/{projectId}/adsense", projectId);
    }

    public void completeAdSenseOAuth(Long projectId, String code, String redirectUri) {
        try {
            restClient.post()
                    .uri("/api/internal/analytics/projects/{projectId}/adsense/oauth-callback", projectId)
                    .headers(this::setAuthorization)
                    .body(Map.of("code", code, "redirectUri", redirectUri))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw translate(e);
        } catch (RestClientException e) {
            throw new AnalyticsServiceException(
                    "analytics-serviceのadsense/oauth-callback呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    private <T> T get(String uriTemplate, Long projectId, Class<T> type) {
        try {
            return restClient.get()
                    .uri(uriTemplate, projectId)
                    .headers(this::setAuthorization)
                    .retrieve()
                    .body(type);
        } catch (RestClientResponseException e) {
            throw translate(e);
        } catch (RestClientException e) {
            throw new AnalyticsServiceException("analytics-serviceの呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    private void put(String uriTemplate, Long projectId, Map<String, String> body) {
        try {
            restClient.put()
                    .uri(uriTemplate, projectId)
                    .headers(this::setAuthorization)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw translate(e);
        } catch (RestClientException e) {
            throw new AnalyticsServiceException("analytics-serviceの呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    private void delete(String uriTemplate, Long projectId) {
        try {
            restClient.delete()
                    .uri(uriTemplate, projectId)
                    .headers(this::setAuthorization)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw translate(e);
        } catch (RestClientException e) {
            throw new AnalyticsServiceException("analytics-serviceの呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /** 4xx(analytics-service側の入力値検証等)はIllegalArgumentExceptionとして呼び出し元へ再送出する。 */
    private RuntimeException translate(RestClientResponseException e) {
        if (e.getStatusCode().is4xxClientError()) {
            return new IllegalArgumentException(bodyOrMessage(e));
        }
        return new AnalyticsServiceException("analytics-serviceの呼び出しに失敗しました: " + bodyOrMessage(e), e);
    }

    private String bodyOrMessage(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        return (body != null && !body.isBlank()) ? body : e.getMessage();
    }

    private void setAuthorization(HttpHeaders headers) {
        String bearerToken = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        }
    }
}
