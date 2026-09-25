package com.letsblog.publishing.client;

import com.letsblog.publishing.service.IdentityServiceUnavailableException;
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
 * identity-serviceが所有するドメイン({@code user_site_authors}・{@code project_users})への
 * 内部ブリッジ。
 *
 * <p>{@code PostPublishService#resolveAuthorId}(#575設計判断4「著者マッピング双方向ブリッジ」の
 * 読み取り側、issue #707)と、{@code ArticlePreviewController}のプロジェクトメンバー判定
 * ({@code AdminAuthorizationService#requireProjectMemberOrAdmin}、issue #712)が使う。
 *
 * <p>issue #583でこれらの所有権がlegacy-apiからidentity-serviceへ移ったのに伴い、
 * 向き先を{@code app.legacy-api-uri}からidentity-serviceへ、パスを
 * {@code /api/internal/project/...}から{@code /api/internal/identity/...}へ変更した。
 *
 * <p>認証は呼び出し元ユーザーのBearerトークンをそのまま転送する。
 */
@Component
public class IdentityBridgeClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final HttpServletRequest request;

    public IdentityBridgeClient(
            RestClient.Builder builder,
            @Value("${app.identity-service-uri}") String identityServiceUri,
            HttpServletRequest request) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.baseUrl(identityServiceUri).requestFactory(requestFactory).build();
        this.request = request;
    }

    private record UserSiteAuthorResponse(String cmsAuthorId) {
    }

    private record CacheUserSiteAuthorRequest(Long userId, Long siteId, String cmsAuthorId) {
    }

    /** PostPublishService#resolveAuthorIdが使う。未登録ならOptional.empty。 */
    public Optional<String> findUserSiteAuthor(Long userId, Long siteId) {
        try {
            UserSiteAuthorResponse result = authorized(restClient.get()
                    .uri("/api/internal/identity/user-site-authors/{userId}/{siteId}", userId, siteId))
                    .retrieve()
                    .body(UserSiteAuthorResponse.class);
            return Optional.ofNullable(result).map(UserSiteAuthorResponse::cmsAuthorId);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw new IllegalStateException(
                    "identity-serviceの著者マッピング照会呼び出しに失敗しました: " + bodyOrMessage(e), e);
        } catch (RestClientException e) {
            throw new IllegalStateException(
                    "identity-serviceの著者マッピング照会呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * PostPublishService#resolveAuthorIdのメール検索フォールバックが見つけた著者IDを
     * {@code user_site_authors}へキャッシュする(#575設計判断4の3.)。
     */
    public void cacheUserSiteAuthor(Long userId, Long siteId, String cmsAuthorId) {
        try {
            authorized(restClient.post().uri("/api/internal/identity/user-site-authors"))
                    .body(new CacheUserSiteAuthorRequest(userId, siteId, cmsAuthorId))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new IllegalStateException(
                    "identity-serviceの著者マッピング保存呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * {@code AdminAuthorizationService#requireProjectMemberOrAdmin}が使う、プロジェクトメンバー判定
     * (issue #712)。呼び出し自体が失敗した場合は、認可判定を素通しさせないよう例外を伝播させる
     * (fail closed)。
     */
    public boolean isProjectMember(Long projectId, Long userId) {
        try {
            Boolean result = authorized(restClient.get()
                    .uri("/api/internal/identity/projects/{projectId}/members/{userId}", projectId, userId))
                    .retrieve()
                    .body(Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (RestClientException e) {
            throw new IdentityServiceUnavailableException(
                    "identity-serviceのプロジェクトメンバー判定呼び出しに失敗しました: " + e.getMessage(), e);
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
