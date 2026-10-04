package com.letsblog.publishing.client;

import com.letsblog.publishing.service.InvalidRechartsTagException;
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
 * content-serviceの{@code /api/internal/content/**}内部ブリッジ(issue #576で実装済み、
 * {@code InternalPublishPipelineController}/{@code InternalPostBridgeController})を呼び出す
 * クライアント。legacy-apiの{@code ContentServiceClient}から、publishing-serviceへ移設した
 * PostPublishService/PostDeleteServiceが実際に使うメソッドのみを移設した(issue #707、#575設計判断1)。
 *
 * <p>テーマ骨格取得(旧プレビュー経路。Playwrightを持つcontent-serviceの内部ブリッジ)向けの
 * メソッドは、プレビューを実サイトの署名付きURLへ一本化した issue #1564 で削除した。
 */
@Component
public class ContentServiceClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    // customTag/blogcard/amazon/rechartsの展開は外部スクレイピング(blogcard/amazon)やmedia-service
    // 経由のレンダリング(recharts)を伴いうるため、単純なJSON APIより長めのタイムアウトを取る
    // (legacy-api版と同じ値)。
    private static final Duration RENDER_READ_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient renderRestClient;
    private final RestClient restClient;
    private final HttpServletRequest request;

    public ContentServiceClient(
            RestClient.Builder builder, @Value("${app.content-service-uri}") String contentServiceUri,
            HttpServletRequest request) {
        this.renderRestClient = build(builder, contentServiceUri, RENDER_READ_TIMEOUT);
        this.restClient = build(builder, contentServiceUri, READ_TIMEOUT);
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
            PreImageRenderRequest body =
                    new PreImageRenderRequest(markdown == null ? "" : markdown, projectId, productionSite);
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

    /**
     * content-service側の{@code PreImageRenderRequest}に対応するリクエストボディ。
     * プロジェクト未紐付けサイトでは{@code projectId}がnullになりうるため(issue #759)、
     * null値を許容しない{@code Map.of}ではなくrecordで表現する。
     */
    public record PreImageRenderRequest(String markdown, Long projectId, boolean productionSite) {
    }

    public record MarkdownResponse(String markdown) {
    }

    /**
     * Markdown→HTML変換 + [toc]カスタムHTMLテンプレート適用 + 統合CSSラッパー適用
     * (PostPublishService#publishの元のステップ9〜11)。
     */
    public String finalizeHtml(String markdown, Long projectId) {
        try {
            FinalizeHtmlRequest body = new FinalizeHtmlRequest(markdown == null ? "" : markdown, projectId);
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

    /**
     * content-service側の{@code FinalizeHtmlRequest}に対応するリクエストボディ。
     * {@link PreImageRenderRequest}と同じくnullの{@code projectId}を許容する(issue #759)。
     */
    public record FinalizeHtmlRequest(String markdown, Long projectId) {
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

    /** content-serviceの{@code PostLookupResponse}に対応する、サイト+スラッグでの既存投稿の照会結果。 */
    public record PostSlugLookup(String wpPostId, String status) {
    }

    /**
     * サイトキー+スラッグに対応する既存投稿を引き当てる(issue #1341)。content-serviceの
     * {@code GET /api/posts/{site}/by-slug/{slug}}は拡張の{@code letsBlog.publish}が使う
     * {@code lookupExistingPost}と同じ識別規則で、レビューの再実行で記事を重複させないために使う。
     * 該当が無ければ空。
     */
    public Optional<PostSlugLookup> findPostBySlug(String siteKey, String slug) {
        try {
            PostSlugLookup result = authorized(restClient.get()
                    .uri("/api/posts/{site}/by-slug/{slug}", siteKey, slug))
                    .retrieve()
                    .body(PostSlugLookup.class);
            return Optional.ofNullable(result);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw new IllegalStateException("content-serviceのスラッグ照会呼び出しに失敗しました: " + bodyOrMessage(e), e);
        } catch (RestClientException e) {
            throw new IllegalStateException("content-serviceのスラッグ照会呼び出しに失敗しました: " + e.getMessage(), e);
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
