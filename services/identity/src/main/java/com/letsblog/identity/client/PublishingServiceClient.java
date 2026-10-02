package com.letsblog.identity.client;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * publishing-serviceの{@code /api/internal/publishing/**}への内部ブリッジ(issue #583で
 * legacy-apiから移設)。
 *
 * <p>CMS(WordPress)側のユーザー作成・更新の実処理はpublishing-serviceが持つ(#707、#575設計判断4)。
 * identity-serviceは{@code project_users}への反映と、返ってきた{@code cmsAuthorId}の
 * {@code user_site_authors}への永続化だけを担う。
 *
 * <p>認証は他サービスの内部ブリッジクライアントと同じく、呼び出し元のBearerトークンを転送する。
 * リクエストの無いスレッド(Rabbitリスナー、issue #1324)ではサービス自身のトークンを使う({@link OutboundAuthHeaders})。
 */
@Component
public class PublishingServiceClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    private final RestClient restClient;
    private final OutboundAuthHeaders authHeaders;

    public PublishingServiceClient(
            RestClient.Builder builder,
            @Value("${app.publishing-service-uri}") String publishingServiceUri,
            OutboundAuthHeaders authHeaders) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.baseUrl(publishingServiceUri).requestFactory(requestFactory).build();
        this.authHeaders = authHeaders;
    }

    public record AuthorProvisioningRequest(
            String email, String wpRole, String firstName, String lastName, String displayName,
            String websiteUrl, String bio, String locale) {
    }

    public record AuthorProvisioningResponse(String cmsAuthorId) {
    }

    /**
     * {@code ProjectUserSyncService#provisionUserOnSite}が使う。CMS側の権限不足・接続失敗時は
     * {@link PublishingServiceException}を投げる。
     */
    public AuthorProvisioningResponse provisionAuthor(String siteKey, AuthorProvisioningRequest requestBody) {
        try {
            AuthorProvisioningResponse result = authorized(restClient.post()
                    .uri("/api/internal/publishing/sites/{siteKey}/authors", siteKey))
                    .body(requestBody)
                    .retrieve()
                    .body(AuthorProvisioningResponse.class);
            if (result == null) {
                throw new PublishingServiceException("publishing-serviceから空の応答を受け取りました", null);
            }
            return result;
        } catch (RestClientException e) {
            throw new PublishingServiceException(
                    "publishing-serviceの著者プロビジョニング呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    private RestClient.RequestBodySpec authorized(RestClient.RequestBodySpec spec) {
        return spec.headers(authHeaders.current());
    }
}
