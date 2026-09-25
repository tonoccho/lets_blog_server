package com.letsblog.publishing.client;

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
 * media-serviceが所有する{@code project_image_settings}への内部ブリッジ。
 *
 * <p>記事投稿時に画像をリサイズする長編の目標pxを解決する。
 * issue #583で{@code project_image_settings}の所有権がlegacy-apiからmedia-serviceへ移ったのに伴い、
 * 向き先を{@code app.legacy-api-uri}からmedia-serviceへ変更した
 * (パスも{@code /api/internal/project/...}から{@code /api/internal/media/...}へ)。
 *
 * <p>認証は呼び出し元ユーザーのBearerトークンをそのまま転送する。
 */
@Component
public class MediaSettingsBridgeClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final HttpServletRequest request;
    private final int defaultArticleImageLongEdgePx;

    public MediaSettingsBridgeClient(
            RestClient.Builder builder,
            @Value("${app.media-service-uri}") String mediaServiceUri,
            @Value("${app.default-article-image-long-edge-px}") int defaultArticleImageLongEdgePx,
            HttpServletRequest request) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.baseUrl(mediaServiceUri).requestFactory(requestFactory).build();
        this.request = request;
        this.defaultArticleImageLongEdgePx = defaultArticleImageLongEdgePx;
    }

    private record ArticleImageLongEdgePxResponse(int value) {
    }

    /**
     * 記事投稿時に画像をリサイズする長編の目標pxを解決する。projectId未指定時は
     * media-serviceへ問い合わせず、本サービスに設定されたアプリ全体のデフォルト値をそのまま返す。
     */
    public int resolveArticleImageLongEdgePx(Long projectId) {
        if (projectId == null) {
            return defaultArticleImageLongEdgePx;
        }
        try {
            ArticleImageLongEdgePxResponse result = authorized(restClient.get()
                    .uri("/api/internal/media/projects/{projectId}/article-image-long-edge-px", projectId))
                    .retrieve()
                    .body(ArticleImageLongEdgePxResponse.class);
            return result == null ? defaultArticleImageLongEdgePx : result.value();
        } catch (RestClientException e) {
            throw new IllegalStateException(
                    "media-serviceの画像リサイズ設定照会呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    private RestClient.RequestHeadersSpec<?> authorized(RestClient.RequestHeadersSpec<?> spec) {
        String bearerToken = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (bearerToken != null && !bearerToken.isBlank()) {
            spec.header(HttpHeaders.AUTHORIZATION, bearerToken);
        }
        return spec;
    }
}
