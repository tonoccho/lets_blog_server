package com.letsblog.publishing.client;

import jakarta.servlet.http.HttpServletRequest;
import java.net.http.HttpClient;
import java.time.Duration;
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
 * legacy-apiの{@code /api/internal/project/**}内部ブリッジ(issue #707、#575設計判断4「著者マッピング
 * 双方向ブリッジ」の読み取り側)を呼び出すクライアント。{@code user_site_authors}の所有権は
 * (ProjectUser/ProjectUserSyncServiceと同じ理由で)当面legacy-apiに残るため、publishing-serviceへ
 * 移設した{@code PostPublishService#resolveAuthorId}は、このクライアント経由で対応表を参照・
 * キャッシュ書き込みする。
 *
 * <p>あわせて、投稿画像の長編リサイズ目標px(project_image_settings、AI画像生成ドメインのため
 * legacy-apiに残る)の解決もこのクライアント経由で行う({@code ProjectService#resolveArticleImageLongEdgePx}
 * と同じ値)。
 */
@Component
public class LegacyApiBridgeClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final HttpServletRequest request;
    private final int defaultArticleImageLongEdgePx;

    public LegacyApiBridgeClient(
            RestClient.Builder builder, @Value("${app.legacy-api-uri}") String legacyApiUri,
            @Value("${app.default-article-image-long-edge-px}") int defaultArticleImageLongEdgePx,
            HttpServletRequest request) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.baseUrl(legacyApiUri).requestFactory(requestFactory).build();
        this.request = request;
        this.defaultArticleImageLongEdgePx = defaultArticleImageLongEdgePx;
    }

    private record UserSiteAuthorResponse(String cmsAuthorId) {
    }

    private record CacheUserSiteAuthorRequest(Long userId, Long siteId, String cmsAuthorId) {
    }

    private record ArticleImageLongEdgePxResponse(int value) {
    }

    /** PostPublishService#resolveAuthorIdが使う。未登録ならOptional.empty。 */
    public Optional<String> findUserSiteAuthor(Long userId, Long siteId) {
        try {
            UserSiteAuthorResponse result = authorized(restClient.get()
                    .uri("/api/internal/project/user-site-authors/{userId}/{siteId}", userId, siteId))
                    .retrieve()
                    .body(UserSiteAuthorResponse.class);
            return Optional.ofNullable(result).map(UserSiteAuthorResponse::cmsAuthorId);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw new IllegalStateException(
                    "legacy-apiの著者マッピング照会呼び出しに失敗しました: " + bodyOrMessage(e), e);
        } catch (RestClientException e) {
            throw new IllegalStateException("legacy-apiの著者マッピング照会呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * PostPublishService#resolveAuthorIdのメール検索フォールバックが見つけた著者IDを
     * user_site_authorsへキャッシュする(#575設計判断4の3.)。
     */
    public void cacheUserSiteAuthor(Long userId, Long siteId, String cmsAuthorId) {
        try {
            authorized(restClient.post().uri("/api/internal/project/user-site-authors"))
                    .body(new CacheUserSiteAuthorRequest(userId, siteId, cmsAuthorId))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new IllegalStateException("legacy-apiの著者マッピング保存呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * 記事投稿時に画像をリサイズする長編の目標pxを解決する(legacy-apiの
     * {@code ProjectService#resolveArticleImageLongEdgePx}と同じ値)。projectId未指定時は
     * legacy-apiへ問い合わせず、本サービスに設定されたアプリ全体のデフォルト値をそのまま返す。
     */
    public int resolveArticleImageLongEdgePx(Long projectId) {
        if (projectId == null) {
            return defaultArticleImageLongEdgePx;
        }
        try {
            ArticleImageLongEdgePxResponse result = authorized(restClient.get()
                    .uri("/api/internal/project/projects/{projectId}/article-image-long-edge-px", projectId))
                    .retrieve()
                    .body(ArticleImageLongEdgePxResponse.class);
            return result == null ? defaultArticleImageLongEdgePx : result.value();
        } catch (RestClientException e) {
            throw new IllegalStateException(
                    "legacy-apiの画像リサイズ設定照会呼び出しに失敗しました: " + e.getMessage(), e);
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
