package com.letsblog.api.client;

import com.letsblog.api.service.InvalidPlantUmlTagException;
import com.letsblog.api.service.InvalidRechartsTagException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * postsテーブル・カスタムタグ/組み込みタグのレンダリングパイプライン・project_content_settings・
 * 記事プレビューのテーマ骨格取得(Playwright)の所有権がcontent-serviceへ移った(issue #576)ことに
 * 伴う内部ブリッジ。PostPublishService/PostDeleteService/WordPressSiteProvisioningService/
 * ProjectService/ArticlePreviewService(いずれもCMS/Site/project_userへの深い依存のためissue #575
 * まで引き続きlegacy-apiに残る)は、postsテーブルへJPAで直接アクセスできなくなった(ADR-0004、
 * lbs_contentスキーマ)ため、この内部ブリッジ経由で読み書きする。
 *
 * <p>認証は、media-service(#573)のCmsBridgeClient/ai-service(#574)のLegacyApiBridgeClientと同じ
 * 暫定策として、呼び出し元のBearerトークンをそのまま転送する。
 */
@Component
public class ContentServiceClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    // customTag/blogcard/amazon/rechartsの展開は外部スクレイピング(blogcard/amazon)やmedia-service
    // 経由のレンダリング(recharts)を伴いうるため、単純なJSON APIより長めのタイムアウトを取る。
    private static final Duration RENDER_READ_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    // Playwrightによる実ページナビゲーション(PreviewSkeletonFetcher)は最大30秒程度かかりうる
    // (REAL_POST_NAVIGATION_TIMEOUT_MS参照)ため、それより余裕を持たせる。
    private static final Duration PREVIEW_SKELETON_READ_TIMEOUT = Duration.ofSeconds(40);

    private final RestClient renderRestClient;
    private final RestClient restClient;
    private final RestClient previewSkeletonRestClient;
    private final HttpServletRequest request;

    public ContentServiceClient(
            RestClient.Builder builder, @Value("${app.content-service-uri}") String contentServiceUri,
            HttpServletRequest request) {
        this.renderRestClient = build(builder, contentServiceUri, RENDER_READ_TIMEOUT);
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

    /**
     * カスタムタグ→[blogcard]→[amazon]→[recharts]の順で展開する(PostPublishService#publishの
     * 元のステップ1〜4)。[recharts]タグの記法・データが不正な場合、content-service側は400を返す。
     * このメソッドはそれを検知し、GlobalExceptionHandlerが引き続き400として扱えるよう
     * {@link InvalidRechartsTagException}へ変換して再送出する(issue #340と同じ挙動を保つ)。
     */
    public String renderPreImage(String markdown, Long projectId, boolean productionSite) {
        try {
            Map<String, Object> body = Map.of(
                    "markdown", markdown == null ? "" : markdown, "projectId", projectId,
                    "productionSite", productionSite);
            MarkdownResponse result = authorized(renderRestClient.post().uri("/api/internal/content/render/pre-image"))
                    .body(body)
                    .retrieve()
                    .body(MarkdownResponse.class);
            return result == null ? markdown : result.markdown();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.BAD_REQUEST) {
                throw new InvalidRechartsTagException(bodyOrMessage(e));
            }
            throw new IllegalStateException("content-serviceのレンダリング呼び出しに失敗しました: " + bodyOrMessage(e), e);
        } catch (RestClientException e) {
            throw new IllegalStateException("content-serviceのレンダリング呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    public record MarkdownResponse(String markdown) {
    }

    /**
     * Markdown→HTML変換 + [toc]カスタムHTMLテンプレート適用 + 統合CSSラッパー適用
     * (PostPublishService#publishの元のステップ9〜11)。
     */
    public String finalizeHtml(String markdown, Long projectId) {
        try {
            Map<String, Object> body = Map.of("markdown", markdown == null ? "" : markdown, "projectId", projectId);
            HtmlResponse result = authorized(renderRestClient.post().uri("/api/internal/content/render/finalize-html"))
                    .body(body)
                    .retrieve()
                    .body(HtmlResponse.class);
            if (result == null) {
                throw new IllegalStateException("content-serviceから空の応答を受け取りました");
            }
            return result.html();
        } catch (RestClientException e) {
            throw new IllegalStateException("content-serviceのHTML変換呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    public record HtmlResponse(String html) {
    }

    public record PostBridgeResponse(
            Long siteId, String wpPostId, String slug, String status, String uploadedImagesJson,
            String categories, LocalDateTime publishScheduledAt, LocalDateTime lastPublishedAt) {
    }

    /** PostPublishService#loadPriorUploadedImages/PostDeleteService#deleteが使う。該当が無ければ空。 */
    public Optional<PostBridgeResponse> findPost(Long siteId, String wpPostId) {
        try {
            PostBridgeResponse result = authorized(restClient.get()
                    .uri("/api/internal/content/posts?siteId={siteId}&wpPostId={wpPostId}", siteId, wpPostId))
                    .retrieve()
                    .body(PostBridgeResponse.class);
            return Optional.ofNullable(result);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw new IllegalStateException("content-serviceの投稿照会呼び出しに失敗しました: " + bodyOrMessage(e), e);
        } catch (RestClientException e) {
            throw new IllegalStateException("content-serviceの投稿照会呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /** PostPublishService#upsertPostRecordが使う。 */
    public void upsertPost(
            Long siteId, String wpPostId, String slug, String status, String uploadedImagesJson,
            String categories, LocalDateTime publishScheduledAt) {
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("siteId", siteId);
            body.put("wpPostId", wpPostId);
            body.put("slug", slug);
            body.put("status", status);
            body.put("uploadedImagesJson", uploadedImagesJson);
            body.put("categories", categories);
            body.put("publishScheduledAt", publishScheduledAt);
            authorized(restClient.put().uri("/api/internal/content/posts"))
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new IllegalStateException("content-serviceの投稿反映呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /** PostDeleteService#deleteが使う。該当が無ければ何もしない。 */
    public void markTrashed(Long siteId, String wpPostId) {
        try {
            authorized(restClient.post().uri("/api/internal/content/posts/mark-trashed"))
                    .body(Map.of("siteId", siteId, "wpPostId", wpPostId))
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (req, res) -> { })
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new IllegalStateException("content-serviceの投稿削除反映呼び出しに失敗しました: " + e.getMessage(), e);
        }
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

    private String bodyOrMessage(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        return (body != null && !body.isBlank()) ? body : e.getMessage();
    }
}
