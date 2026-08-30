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

/**
 * project_content_settingsの所有権がcontent-serviceへ移った(issue #576)ことに伴う内部ブリッジ。
 * ProjectService/WordPressSiteProvisioningService(いずれも引き続きlegacy-apiに残る)が使う。
 *
 * <p>投稿の公開・削除パイプライン(PostPublishService/PostDeleteService)向けのメソッド群
 * (renderPreImage/finalizeHtml/findPost/upsertPost/markTrashed)は、それらのクラスと共に
 * publishing-serviceへ移設した(issue #707)ため削除した(publishing-service側の同名クラス
 * {@code com.letsblog.publishing.client.ContentServiceClient}参照)。記事プレビューのテーマ骨格取得
 * (fetchAndSplice/fetchRealPost)も、ArticlePreviewServiceと共にpublishing-serviceへ移設した
 * (issue #712、Epic #551 C6-6)ため同様に削除した。
 *
 * <p>認証は、media-service(#573)のCmsBridgeClient/ai-service(#574)のLegacyApiBridgeClientと同じ
 * 暫定策として、呼び出し元のBearerトークンをそのまま転送する。
 */
@Component
public class ContentServiceClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final HttpServletRequest request;

    public ContentServiceClient(
            RestClient.Builder builder, @Value("${app.content-service-uri}") String contentServiceUri,
            HttpServletRequest request) {
        this.restClient = build(builder, contentServiceUri, READ_TIMEOUT);
        this.request = request;
    }

    private RestClient build(RestClient.Builder builder, String baseUrl, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return builder.clone().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    /** WordPressSiteProvisioningService#deleteSiteが使う。 */
    public void deletePostsBySite(Long siteId) {
        try {
            authorized(restClient.delete().uri("/api/internal/content/posts/by-site/{siteId}", siteId))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new IllegalStateException("content-serviceのサイト別投稿削除呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    public record ContentSettingsResponse(String cssSelectorPrefix) {
    }

    /** ProjectService#toResponseが使う。未設定のプロジェクトはcssSelectorPrefix=null。 */
    public String getCssSelectorPrefix(Long projectId) {
        try {
            ContentSettingsResponse result = authorized(restClient.get()
                    .uri("/api/internal/content/projects/{projectId}/content-settings", projectId))
                    .retrieve()
                    .body(ContentSettingsResponse.class);
            return result == null ? null : result.cssSelectorPrefix();
        } catch (RestClientException e) {
            throw new IllegalStateException("content-serviceのコンテンツ設定呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /** ProjectService#updateCssSelectorPrefixが使う。 */
    public void updateCssSelectorPrefix(Long projectId, String cssSelectorPrefix) {
        try {
            authorized(restClient.put().uri("/api/internal/content/projects/{projectId}/content-settings", projectId))
                    .body(new ContentSettingsResponse(cssSelectorPrefix))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new IllegalStateException("content-serviceのコンテンツ設定更新呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    private RestClient.RequestBodySpec authorized(RestClient.RequestBodySpec spec) {
        String bearerToken = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (bearerToken != null && !bearerToken.isBlank()) {
            spec.header(HttpHeaders.AUTHORIZATION, bearerToken);
        }
        return spec;
    }

    private RestClient.RequestHeadersSpec<?> authorized(RestClient.RequestHeadersSpec<?> spec) {
        String bearerToken = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (bearerToken != null && !bearerToken.isBlank()) {
            spec.header(HttpHeaders.AUTHORIZATION, bearerToken);
        }
        return spec;
    }
}
