package com.letsblog.ai.client;

import com.letsblog.ai.service.IdentityServiceUnavailableException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * identity-serviceが所有する{@code project_users}へのプロジェクトメンバー判定
 * ({@code AdminAuthorizationService#requireProjectMemberOrAdmin}が使う)。
 *
 * <p>issue #583で{@code project_users}の所有権がlegacy-apiからidentity-serviceへ移ったのに伴い、
 * legacy-apiの{@code AiBridgeController}({@code /api/internal/ai/...})から
 * identity-serviceの{@code /api/internal/identity/...}へ向き先を変えた。
 * 同じ判定がlegacy-apiの4コントローラに重複していたのを1本へ統合したものである。
 *
 * <p>認証は呼び出し元ユーザーのBearerトークンをそのまま転送する
 * (identity-service側の{@code /api/internal/**}は追加の認可を行わない前提)。
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
