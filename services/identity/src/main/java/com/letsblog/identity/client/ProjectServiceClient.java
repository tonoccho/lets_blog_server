package com.letsblog.identity.client;

import com.letsblog.identity.service.ProjectNotFoundException;
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
 * project-serviceの{@code /api/internal/project/**}への内部ブリッジ(issue #583)。
 *
 * <p>{@code project_users}/{@code user_site_authors}の所有権がidentity-serviceへ移った(#583)一方、
 * プロジェクトとサイト本体の所有権はproject-service(#577 stage2)にある。
 * {@link com.letsblog.identity.service.ProjectUserSyncService}が「このプロジェクトに紐付く
 * WordPress環境はどれか」「そのサイトのsiteKeyは何か」を解決するために使う。
 *
 * <p>必要なのはプロジェクトとサイトの照会だけなので、legacy-apiにあった同名クラスのうち
 * 資格情報・GitHubトークン・タグデザインの照会は移していない(それぞれ所有サービスが直接扱う)。
 *
 * <p>認証は他サービスの内部ブリッジクライアントと同じく、呼び出し元のBearerトークンを転送する。
 */
@Component
public class ProjectServiceClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final HttpServletRequest request;

    public ProjectServiceClient(
            RestClient.Builder builder,
            @Value("${app.project-service-uri}") String projectServiceUri,
            HttpServletRequest request) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.baseUrl(projectServiceUri).requestFactory(requestFactory).build();
        this.request = request;
    }

    /**
     * プロジェクトの基本情報。環境別サイトID(ローカル/テスト/本番)は未設定ならnullになる。
     * {@code ProjectUserSyncService}が同期対象サイトを決めるために使う。
     */
    public record ProjectBridge(
            Long id, String name, String slug, String masterEnvironment,
            Long localSiteId, Long testSiteId, Long productionSiteId, String githubRepository) {
    }

    /** サイトの基本情報。著者プロビジョニングの宛先を決めるのに{@code siteKey}を使う。 */
    public record SiteBridge(Long id, String siteKey, String name, String baseUrl) {
    }

    /** 未登録なら{@link ProjectNotFoundException}。 */
    public ProjectBridge getProject(Long projectId) {
        try {
            ProjectBridge result = authorized(restClient.get()
                    .uri("/api/internal/project/projects/{projectId}", projectId))
                    .retrieve()
                    .body(ProjectBridge.class);
            if (result == null) {
                throw new IllegalStateException("project-serviceから空の応答を受け取りました");
            }
            return result;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new ProjectNotFoundException("id " + projectId + " のプロジェクトは登録されていません");
            }
            throw new IllegalStateException(
                    "project-serviceのプロジェクト照会呼び出しに失敗しました: " + bodyOrMessage(e), e);
        } catch (RestClientException e) {
            throw new IllegalStateException(
                    "project-serviceのプロジェクト照会呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /** 未登録ならempty。 */
    public Optional<SiteBridge> getSite(Long siteId) {
        try {
            SiteBridge result = authorized(restClient.get()
                    .uri("/api/internal/project/sites/{id}", siteId))
                    .retrieve()
                    .body(SiteBridge.class);
            return Optional.ofNullable(result);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw new IllegalStateException(
                    "project-serviceのサイト照会呼び出しに失敗しました: " + bodyOrMessage(e), e);
        } catch (RestClientException e) {
            throw new IllegalStateException(
                    "project-serviceのサイト照会呼び出しに失敗しました: " + e.getMessage(), e);
        }
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
