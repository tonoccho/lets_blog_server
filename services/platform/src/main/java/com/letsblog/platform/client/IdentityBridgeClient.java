package com.letsblog.platform.client;

import com.letsblog.platform.service.IdentityServiceUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * identity-serviceが所有する{@code project_users}へのプロジェクトメンバー判定(issue #1808)。ai-serviceの同名クライアントと
 * 同じ経路({@code /api/internal/identity/projects/{projectId}/members/{userId}})で、呼び出し元ユーザーのBearerトークンを
 * そのまま転送する。platform-serviceはproject_usersへクロススキーマアクセスできない(ADR-0004)。
 */
@Component
public class IdentityBridgeClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;

    public IdentityBridgeClient(
            RestClient.Builder builder, @Value("${app.identity-service-uri}") String identityServiceUri) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.baseUrl(identityServiceUri).requestFactory(requestFactory).build();
    }

    /** 操作者(userId)がプロジェクトのメンバーかどうかを判定する。 */
    public boolean isProjectMember(Long projectId, Long userId, String bearerToken) {
        try {
            Boolean result = restClient.get()
                    .uri("/api/internal/identity/projects/{projectId}/members/{userId}", projectId, userId)
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .retrieve()
                    .body(Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (RestClientException e) {
            throw new IdentityServiceUnavailableException(
                    "identity-serviceのプロジェクトメンバー判定呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    private void setAuthorization(HttpHeaders headers, String bearerToken) {
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        }
    }
}
