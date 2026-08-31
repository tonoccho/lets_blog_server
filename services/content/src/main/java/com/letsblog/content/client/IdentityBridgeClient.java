package com.letsblog.content.client;

import com.letsblog.content.dto.RoleOptionResponse;
import com.letsblog.content.service.IdentityServiceUnavailableException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * identity-serviceが所有するドメイン({@code project_users}・{@code roles})への内部ブリッジ。
 *
 * <p>issue #583でこれらの所有権がlegacy-apiからidentity-serviceへ移ったのに伴い、
 * legacy-apiの{@code ContentBridgeController}({@code /api/internal/content/...})から
 * identity-serviceの{@code /api/internal/identity/...}へ向き先を変えた。
 *
 * <p>認証は呼び出し元ユーザーのBearerトークンをそのまま転送する。
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

    /** AdminAuthorizationService#requireProjectMemberOrAdminが使う、プロジェクトメンバー判定。 */
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

    /** MetadataController#rolesが使う、ロールの表示名一覧(特定の権限を要求しない、参照専用)。 */
    public List<RoleOptionResponse> listRoles(String bearerToken) {
        try {
            RoleOptionResponse[] result = restClient.get()
                    .uri("/api/internal/identity/roles")
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .retrieve()
                    .body(RoleOptionResponse[].class);
            return result == null ? List.of() : List.of(result);
        } catch (RestClientException e) {
            throw new IdentityServiceUnavailableException(
                    "identity-serviceのロール一覧呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    private void setAuthorization(HttpHeaders headers, String bearerToken) {
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        }
    }
}
