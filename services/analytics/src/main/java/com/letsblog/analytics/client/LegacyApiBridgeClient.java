package com.letsblog.analytics.client;

import com.letsblog.analytics.service.IdentityServiceUnavailableException;
import com.letsblog.analytics.service.ProjectNotFoundException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * GoogleAnalyticsReportService/AdSenseReportService/AdminAuthorizationServiceがまだlegacy-apiに
 * 残るドメイン(Project、project_user)へアクセスするための内部ブリッジ(issue #578)。
 *
 * <p>project-serviceはまだ本番サイト等の一般的なプロジェクト情報については未抽出(Phase 19の他Issue、
 * #577はSSH鍵ペア/組み込みタグデザインのみ移設済み)のため、レポート取得可否判定に必要な
 * 「本番サイトが紐付いているか」「プロジェクトメンバー判定」は、引き続きlegacy-api側のデータ・
 * ロジックに依存する。ai-service(#574)のLegacyApiBridgeClientと同じ暫定策(呼び出し元のBearer
 * トークンをそのまま転送する。legacy-api側の対応エンドポイント(AnalyticsBridgeController、
 * {@code /api/internal/analytics/**})はCmsMediaBridgeController/AiBridgeControllerと同じ方針で
 * 追加の認可チェックを行わない=呼び出し元(analytics-service)が既にrequireProjectMemberOrAdmin等を
 * 済ませたリクエストのトークンをそのまま転送してもらう想定)。
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

    /** 操作者(userId)がプロジェクトのメンバーかどうかを判定する(AdminAuthorizationServiceが使う)。 */
    public boolean isProjectMember(Long projectId, Long userId, String bearerToken) {
        try {
            Boolean result = restClient.get()
                    .uri("/api/internal/analytics/projects/{projectId}/members/{userId}", projectId, userId)
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .retrieve()
                    .body(Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (RestClientException e) {
            throw new IdentityServiceUnavailableException(
                    "legacy-apiのプロジェクトメンバー判定呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    public record ProjectEligibility(boolean hasProductionSite) {
    }

    /**
     * GoogleAnalyticsReportService/AdSenseReportServiceが使う、レポート取得可否判定用のプロジェクト情報。
     * プロジェクトが存在しない場合は{@link ProjectNotFoundException}を投げる(legacy-apiの
     * ProjectNotFoundExceptionと同じ404マッピング)。
     */
    public ProjectEligibility getProjectEligibility(Long projectId, String bearerToken) {
        try {
            ProjectEligibility eligibility = restClient.get()
                    .uri("/api/internal/analytics/projects/{projectId}", projectId)
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .retrieve()
                    .body(ProjectEligibility.class);
            if (eligibility == null) {
                throw new IdentityServiceUnavailableException("legacy-apiから空の応答を受け取りました", null);
            }
            return eligibility;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatusCode.valueOf(404)) {
                throw new ProjectNotFoundException("id " + projectId + " のプロジェクトは登録されていません");
            }
            throw new IdentityServiceUnavailableException(
                    "legacy-apiのプロジェクト情報取得に失敗しました: " + e.getMessage(), e);
        } catch (RestClientException e) {
            throw new IdentityServiceUnavailableException(
                    "legacy-apiのプロジェクト情報取得に失敗しました: " + e.getMessage(), e);
        }
    }

    private void setAuthorization(HttpHeaders headers, String bearerToken) {
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        }
    }
}
