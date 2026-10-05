package com.letsblog.project.client;

import com.letsblog.project.service.IdentityServiceUnavailableException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * analytics-service が所有する GA4 の認証情報への内部ブリッジ(issue #1578)。本番サイトのプラグインへ送るために、
 * 復号済みの認証情報を読む。認証は呼び出し元ユーザーのBearerトークンをそのまま転送する(analytics-service が
 * プロジェクトのメンバーかadminかを検査する)。
 */
@Component
public class AnalyticsBridgeClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;

    public AnalyticsBridgeClient(
            RestClient.Builder builder, @Value("${app.analytics-service-uri}") String analyticsServiceUri) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.baseUrl(analyticsServiceUri).requestFactory(requestFactory).build();
    }

    /**
     * GA4 の認証情報。{@code configured=false}(未連携)のときは他の値が null。秘密を含むので
     * {@code toString}では伏せる。
     */
    public record GoogleAnalyticsCredentials(
            boolean configured, String propertyId, String clientId, String clientSecret, String refreshToken) {

        @Override
        public String toString() {
            return "GoogleAnalyticsCredentials[configured=" + configured + ", propertyId=" + propertyId + "]";
        }
    }

    public GoogleAnalyticsCredentials googleAnalyticsCredentials(Long projectId, String bearerToken) {
        try {
            GoogleAnalyticsCredentials result = restClient.get()
                    .uri("/api/internal/analytics/projects/{projectId}/google-analytics/credentials", projectId)
                    .headers(headers -> {
                        if (bearerToken != null && !bearerToken.isBlank()) {
                            headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
                        }
                    })
                    .retrieve()
                    .body(GoogleAnalyticsCredentials.class);
            if (result == null) {
                throw new IdentityServiceUnavailableException("analytics-serviceから空の応答を受け取りました", null);
            }
            return result;
        } catch (RestClientException e) {
            throw new IdentityServiceUnavailableException(
                    "analytics-serviceのGA認証情報の取得に失敗しました: " + e.getMessage(), e);
        }
    }
}
