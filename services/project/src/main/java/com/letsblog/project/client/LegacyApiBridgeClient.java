package com.letsblog.project.client;

import com.letsblog.project.service.IdentityServiceUnavailableException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * まだlegacy-apiに残るドメイン(project_user、プロジェクトメンバー判定)へアクセスするための
 * 内部ブリッジ(issue #577)。project_userテーブルの所有権はProject/Siteドメイン(#577の後続stage)と
 * 一緒にproject-serviceへ移る予定だが、このstageではまだ移設しないため、media-service(#573)の
 * CmsBridgeClient/ai-service(#574)・content-service(#576)のLegacyApiBridgeClientと同じ暫定策
 * (呼び出し元のBearerトークンをそのまま転送する)でlegacy-apiの内部ブリッジエンドポイント
 * ({@code /api/internal/content/**}、legacy-api側のContentBridgeController)へ問い合わせる。
 *
 * <p>project_userのメンバー判定はcontent-service向けに公開されている
 * {@code /api/internal/content/projects/{projectId}/members/{userId}}をそのまま再利用する
 * (プロジェクトメンバー判定自体はcontent-service固有のロジックを含まない汎用的なチェックのため)。
 */
@Component
public class LegacyApiBridgeClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;

    public LegacyApiBridgeClient(RestClient.Builder builder, @Value("${app.legacy-api-uri}") String legacyApiUri) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.baseUrl(legacyApiUri).requestFactory(requestFactory).build();
    }

    /** AdminAuthorizationService#requireProjectMemberOrAdminが使う、プロジェクトメンバー判定。 */
    public boolean isProjectMember(Long projectId, Long userId, String bearerToken) {
        try {
            Boolean result = restClient.get()
                    .uri("/api/internal/content/projects/{projectId}/members/{userId}", projectId, userId)
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .retrieve()
                    .body(Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (RestClientException e) {
            throw new IdentityServiceUnavailableException(
                    "legacy-apiのプロジェクトメンバー判定呼び出しに失敗しました: " + e.getMessage(), e);
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
                    .uri("/api/internal/project/users/{userId}/project-ids", userId)
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Long>>() { });
            return result != null ? result : List.of();
        } catch (RestClientException e) {
            throw new IdentityServiceUnavailableException(
                    "legacy-apiの所属プロジェクト一覧呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * ProjectEnvironmentSyncService#syncがDB同期後に呼ぶ、project_user(legacy-apiに残る、issue #577
     * stage2ではまだ移設しない)の内容でのサイト向けWordPressユーザーロール再整合の依頼。
     */
    public void reconcileRolesForSite(Long projectId, Long siteId, String bearerToken) {
        try {
            restClient.post()
                    .uri("/api/internal/project/project-users/{projectId}/sites/{siteId}/reconcile-roles",
                            projectId, siteId)
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new IdentityServiceUnavailableException(
                    "legacy-apiのユーザーロール再整合呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    private void setAuthorization(HttpHeaders headers, String bearerToken) {
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        }
    }
}
