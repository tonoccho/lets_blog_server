package com.letsblog.api.client;

import com.letsblog.api.service.InvalidPlantUmlTagException;
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
 * project_content_settings・記事プレビューのテーマ骨格取得(Playwright)の所有権がcontent-serviceへ
 * 移った(issue #576)ことに伴う内部ブリッジ。ProjectService/ArticlePreviewService(いずれもSite/
 * project_userへの深い依存のためissue #577/#707時点でも引き続きlegacy-apiに残る)が使う。
 *
 * <p>投稿の公開・削除パイプライン(PostPublishService/PostDeleteService)向けのメソッド群
 * (renderPreImage/finalizeHtml/findPost/upsertPost/markTrashed)は、それらのクラスと共に
 * publishing-serviceへ移設した(issue #707)ため削除した(publishing-service側の同名クラス
 * {@code com.letsblog.publishing.client.ContentServiceClient}参照)。
 *
 * <p>認証は、media-service(#573)のCmsBridgeClient/ai-service(#574)のLegacyApiBridgeClientと同じ
 * 暫定策として、呼び出し元のBearerトークンをそのまま転送する。
 */
@Component
public class ContentServiceClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    // Playwrightによる実ページナビゲーション(PreviewSkeletonFetcher)は最大30秒程度かかりうる
    // (REAL_POST_NAVIGATION_TIMEOUT_MS参照)ため、それより余裕を持たせる。
    private static final Duration PREVIEW_SKELETON_READ_TIMEOUT = Duration.ofSeconds(40);

    private final RestClient restClient;
    private final RestClient previewSkeletonRestClient;
    private final HttpServletRequest request;

    public ContentServiceClient(
            RestClient.Builder builder, @Value("${app.content-service-uri}") String contentServiceUri,
            HttpServletRequest request) {
        this.restClient = build(builder, contentServiceUri, READ_TIMEOUT);
        this.previewSkeletonRestClient = build(builder, contentServiceUri, PREVIEW_SKELETON_READ_TIMEOUT);
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

    public record ThemeSkeletonBridgeResponse(String html, boolean available, String reason,
            boolean eyecatchSpliced, String css) {
    }

    /**
     * ArticlePreviewService#renderSkeletonが使う。Playwrightを持つのはcontent-serviceになった
     * (issue #576の注記)ため、CMS/Site認証情報の解決はlegacy-api側で済ませた上で、navigateUrl・
     * 差し替え内容のみを渡してPreviewSkeletonFetcher#fetchAndSpliceを実行してもらう。
     */
    public ThemeSkeletonBridgeResponse fetchAndSplice(
            String url, String titleRendered, String contentRendered, String ourTitle, String ourContentHtml,
            String featuredImageDataUri) {
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("url", url);
            body.put("titleRendered", titleRendered);
            body.put("contentRendered", contentRendered);
            body.put("ourTitle", ourTitle);
            body.put("ourContentHtml", ourContentHtml);
            body.put("featuredImageDataUri", featuredImageDataUri);
            ThemeSkeletonBridgeResponse result = authorized(previewSkeletonRestClient.post()
                    .uri("/api/internal/content/preview-skeleton/fetch-and-splice"))
                    .body(body)
                    .retrieve()
                    .body(ThemeSkeletonBridgeResponse.class);
            if (result == null) {
                throw new InvalidPlantUmlTagException("content-serviceから空の応答を受け取りました");
            }
            return result;
        } catch (RestClientException e) {
            throw new IllegalStateException("content-serviceのテーマ骨格取得呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /** ArticlePreviewService#renderRealPrivatePostが使う。 */
    public ThemeSkeletonBridgeResponse fetchRealPost(String url, String cookieName, String cookieValue) {
        try {
            Map<String, Object> body = Map.of("url", url, "cookieName", cookieName, "cookieValue", cookieValue);
            ThemeSkeletonBridgeResponse result = authorized(previewSkeletonRestClient.post()
                    .uri("/api/internal/content/preview-skeleton/fetch-real-post"))
                    .body(body)
                    .retrieve()
                    .body(ThemeSkeletonBridgeResponse.class);
            if (result == null) {
                throw new IllegalStateException("content-serviceから空の応答を受け取りました");
            }
            return result;
        } catch (RestClientException e) {
            throw new IllegalStateException("content-serviceのテーマ骨格取得呼び出しに失敗しました: " + e.getMessage(), e);
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
