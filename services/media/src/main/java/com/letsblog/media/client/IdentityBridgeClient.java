package com.letsblog.media.client;

import com.letsblog.media.service.IdentityServiceUnavailableException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * media-serviceの{@code AdminAuthorizationService}がプロジェクトメンバー判定を行うための
 * 内部ブリッジ(issue #830)。
 *
 * <p>ダイアグラム・生成画像はいずれも{@code projectId}を持つのでプロジェクト単位で絞れるが、
 * {@code project_users}はmedia-serviceのスキーマに無い(ADR-0004によりクロススキーマ参照は不可)ため、
 * 所有サービスへ問い合わせる。
 *
 * <p>issue #583で{@code project_users}の所有権がlegacy-apiからidentity-serviceへ移ったのに伴い、
 * 向き先を{@code app.legacy-api-uri}からidentity-serviceへ、パスを
 * {@code /api/internal/project/projects/...}から{@code /api/internal/identity/projects/...}へ変更した。
 * #583以前は ai / analytics / content / project / publishing / media の6サービスが、
 * legacy-apiの4本の同一実装のブリッジに分散して問い合わせていたが、identity側の1本へ統合した。
 *
 * <p>呼び出し元(media-service)のBearerトークンではなく、<b>元の利用者のトークンをそのまま
 * 転送する</b>。identity-service側の{@code /api/internal/**}は追加の認可を行わない前提。
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
