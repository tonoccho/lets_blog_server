package com.letsblog.project.client;

import com.letsblog.project.service.IdentityServiceUnavailableException;
import com.letsblog.common.auth.ServiceTokenClient;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * identity-serviceが所有するドメイン({@code project_users}・利用者のGitHubトークン)へ
 * アクセスするための内部ブリッジ。
 *
 * <p>issue #583で{@code project_users}の所有権がlegacy-apiからidentity-serviceへ移ったのに伴い、
 * 向き先を{@code app.legacy-api-uri}からidentity-serviceへ、パスを
 * {@code /api/internal/content/...}・{@code /api/internal/project/...}から
 * {@code /api/internal/identity/...}へ変更した。#583以前は同一実装のメンバー判定が
 * legacy-apiの4コントローラに重複しており、呼び出し側もどれを叩くかがばらついていた。
 *
 * <p>認証は、呼び出し元ユーザーのBearerトークンをそのまま転送する
 * (identity-service側の{@code /api/internal/**}は追加の認可を行わない前提)。
 */
@Component
public class IdentityBridgeClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final ServiceTokenClient serviceTokenClient;

    public IdentityBridgeClient(
            RestClient.Builder builder, @Value("${app.identity-service-uri}") String identityServiceUri,
            ServiceTokenClient serviceTokenClient) {
        this.serviceTokenClient = serviceTokenClient;
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.baseUrl(identityServiceUri).requestFactory(requestFactory).build();
    }

    /** AdminAuthorizationService#requireProjectMemberOrAdminが使う、プロジェクトメンバー判定。 */
    public boolean isProjectMember(Long projectId, Long userId, String bearerToken) {
        try {
            Boolean result = restClient.get()
                    .uri("/api/internal/identity/projects/{projectId}/members/{userId}", projectId, userId)
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .retrieve()
                    .body(Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (RestClientException e) {
            throw new IdentityServiceUnavailableException(
                    "identity-serviceのプロジェクトメンバー判定呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * 操作者が所属するプロジェクトのID一覧(issue #830)。
     *
     * <p>一覧系エンドポイントの「自分がアクセスできる分だけ返す」絞り込みに使う。
     * {@link #isProjectMember}を行ごとに呼ぶとN+1になるためまとめて引く。
     */
    public List<Long> projectIdsForUser(Long userId, String bearerToken) {
        try {
            List<Long> result = restClient.get()
                    .uri("/api/internal/identity/users/{userId}/project-ids", userId)
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Long>>() { });
            return result != null ? result : List.of();
        } catch (RestClientException e) {
            throw new IdentityServiceUnavailableException(
                    "identity-serviceの所属プロジェクト一覧呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * ProjectEnvironmentSyncService#syncがDB同期後に呼ぶ、{@code project_users}の内容での
     * サイト向けWordPressユーザーロール再整合の依頼。
     *
     * <p>同期APIのリクエスト中は利用者のBearerを転送する。ジョブのスレッド(リクエストが無く、取り置いた
     * 利用者のBearerは5分で失効する)ではBearerが渡されないので、サービス自身のClient Credentialsを使う(issue #1723)。
     */
    public void reconcileRolesForSite(Long projectId, Long siteId, String bearerToken) {
        try {
            restClient.post()
                    .uri("/api/internal/identity/project-users/{projectId}/sites/{siteId}/reconcile-roles",
                            projectId, siteId)
                    .headers(headers -> setAuthorization(headers, outsideRequest() && isBlank(bearerToken)
                            ? "Bearer " + serviceTokenClient.getAccessToken() : bearerToken))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new IdentityServiceUnavailableException(
                    "identity-serviceのユーザーロール再整合呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /** 利用者個人のGitHubトークン(暗号化済み)。未設定なら{@code configured=false}。 */
    public record UserGithubToken(boolean configured, byte[] encryptedToken) {
    }

    /**
     * プロジェクト自身のGitHubトークンが未設定のときのフォールバック先(issue #583)。
     * 復号は呼び出し元(project-service)が全サービス共通の{@code APP_ENCRYPTION_KEY}で行う。
     */
    public UserGithubToken userGithubToken(Long userId, String bearerToken) {
        try {
            UserGithubToken result = restClient.get()
                    .uri("/api/internal/identity/users/{userId}/github-token", userId)
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .retrieve()
                    .body(UserGithubToken.class);
            return result != null ? result : new UserGithubToken(false, null);
        } catch (RestClientException e) {
            throw new IdentityServiceUnavailableException(
                    "identity-serviceの利用者GitHubトークン取得呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    private static boolean outsideRequest() {
        return RequestContextHolder.getRequestAttributes() == null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void setAuthorization(HttpHeaders headers, String bearerToken) {
        if (!isBlank(bearerToken)) {
            headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        }
    }
}
