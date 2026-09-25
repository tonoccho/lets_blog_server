package com.letsblog.ai.client;

import com.letsblog.ai.service.IdentityServiceUnavailableException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * project-serviceが所有するプロジェクト情報(GitHubリポジトリとアクセストークン)への内部ブリッジ。
 * {@code ArticlePlanService}がGitHub issue連携で使う。
 *
 * <p>issue #583以前は legacy-api の {@code AiBridgeController}
 * ({@code /api/internal/ai/projects/{id}/github-access})が同じ値を返していた。
 * legacy-api の解体にあたり、GitHubトークンの所有者である project-service へ実装ごと移した。
 *
 * <p>解決順(プロジェクト自身のトークン優先、無ければ操作者本人のユーザー設定へフォールバック)は
 * 移設前と同じで、project-service側が identity-service へ問い合わせて解決する。
 *
 * <p>認証は呼び出し元ユーザーのBearerトークンをそのまま転送する。
 */
@Component
public class ProjectBridgeClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;

    public ProjectBridgeClient(
            RestClient.Builder builder, @Value("${app.project-service-uri}") String projectServiceUri) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.baseUrl(projectServiceUri).requestFactory(requestFactory).build();
    }

    public record GithubAccess(String token, String owner, String repo) {
    }

    /**
     * プロジェクトに紐づくGitHubアクセス情報(トークン・owner/repo)を解決する。
     * GitHubリポジトリ未設定・トークン未設定の場合は409が返り、その本文をそのまま例外メッセージにする
     * (移設前と同じ挙動)。
     */
    public GithubAccess resolveGithubAccess(Long projectId, Long actorUserId, String bearerToken) {
        try {
            GithubAccess access = restClient.get()
                    .uri("/api/internal/project/projects/{projectId}/github-access?actorUserId={actorUserId}",
                            projectId, actorUserId)
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .retrieve()
                    .body(GithubAccess.class);
            if (access == null) {
                throw new IllegalStateException("project-serviceから空の応答を受け取りました");
            }
            return access;
        } catch (RestClientResponseException e) {
            throw new IllegalStateException(bodyOrMessage(e), e);
        } catch (RestClientException e) {
            throw new IdentityServiceUnavailableException(
                    "project-serviceのgithub-access呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    private void setAuthorization(HttpHeaders headers, String bearerToken) {
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        }
    }

    private String bodyOrMessage(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        return (body != null && !body.isBlank()) ? body : e.getMessage();
    }
}
