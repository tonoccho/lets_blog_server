package com.letsblog.api.client;

import jakarta.servlet.http.HttpServletRequest;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * publishing-serviceの{@code /api/internal/publishing/**}内部ブリッジ(issue #707、#575設計判断4
 * 「著者マッピング双方向ブリッジ」の書き込み側)を呼び出すクライアント。{@code CmsAdapter}/
 * {@code CmsAdapterFactory}がpublishing-serviceへ完全移管された(issue #707)ため、
 * {@code ProjectUserSyncService#provisionUserOnSite}が直接呼んでいたCMS側の著者作成/更新を、
 * このブリッジ経由での依頼に置き換えた。返ってきたcmsAuthorIdの{@code user_site_authors}への
 * 永続化は引き続きlegacy-api側の責務。
 */
@Component
public class PublishingServiceClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    private final RestClient restClient;
    private final HttpServletRequest request;

    public PublishingServiceClient(
            RestClient.Builder builder, @Value("${app.publishing-service-uri}") String publishingServiceUri,
            HttpServletRequest request) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.baseUrl(publishingServiceUri).requestFactory(requestFactory).build();
        this.request = request;
    }

    public record AuthorProvisioningRequest(
            String email, String wpRole, String firstName, String lastName, String displayName,
            String websiteUrl, String bio, String locale) {
    }

    public record AuthorProvisioningResponse(String cmsAuthorId) {
    }

    /**
     * ProjectUserSyncService#provisionUserOnSiteが使う。CMS側の権限不足・接続失敗時は
     * {@link PublishingServiceException}を投げる(呼び出し元でCmsApiExceptionと同様に扱う)。
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
        String bearerToken = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (bearerToken != null && !bearerToken.isBlank()) {
            spec.header(HttpHeaders.AUTHORIZATION, bearerToken);
        }
        return spec;
    }
}
