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
 * project-serviceが所有するプロジェクト情報への内部ブリッジ。
 *
 * <p>issue #583以前は legacy-api の {@code AnalyticsBridgeController}
 * ({@code /api/internal/analytics/projects/{id}})が同じ値を返していた。legacy-api の解体にあたり、
 * {@code projects}/{@code sites} の所有者である project-service へ移した。
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

    public record ProjectEligibility(boolean hasProductionSite) {
    }

    /**
     * {@code GoogleAnalyticsReportService}/{@code AdSenseReportService}が使う、
     * レポート取得可否判定用のプロジェクト情報。プロジェクトが存在しない場合は
     * {@link ProjectNotFoundException}を投げる(404マッピングは移設前と同じ)。
     */
    public ProjectEligibility getProjectEligibility(Long projectId, String bearerToken) {
        try {
            ProjectEligibility eligibility = restClient.get()
                    .uri("/api/internal/project/projects/{projectId}/eligibility", projectId)
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .retrieve()
                    .body(ProjectEligibility.class);
            if (eligibility == null) {
                throw new IdentityServiceUnavailableException("project-serviceから空の応答を受け取りました", null);
            }
            return eligibility;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatusCode.valueOf(404)) {
                throw new ProjectNotFoundException("id " + projectId + " のプロジェクトは登録されていません");
            }
            throw new IdentityServiceUnavailableException(
                    "project-serviceのプロジェクト情報取得に失敗しました: " + e.getMessage(), e);
        } catch (RestClientException e) {
            throw new IdentityServiceUnavailableException(
                    "project-serviceのプロジェクト情報取得に失敗しました: " + e.getMessage(), e);
        }
    }

    private void setAuthorization(HttpHeaders headers, String bearerToken) {
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        }
    }
}
