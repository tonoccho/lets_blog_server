package com.letsblog.api.client;

import com.letsblog.common.client.ServiceAuthHeaders;
import com.letsblog.common.client.SyncCallProfile;
import com.letsblog.common.client.SyncServiceClient;
import com.letsblog.common.client.SyncServiceClientErrorException;
import com.letsblog.common.client.SyncServiceException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * analytics-serviceの{@code /api/internal/analytics/projects/{projectId}/**}を呼び出すクライアント
 * (issue #578)。issue #581(C12)でlbs-commonの{@link SyncServiceClient}(タイムアウト・リトライ・
 * サーキットブレーカーの共通実装)へ移行した。方針の詳細はdocs/SYNC_SERVICE_CALLS.md参照。
 *
 * <p>ProjectApiKeyController(Web管理画面向け、プロジェクト単位のAPIキー設定)は、GitHubトークン/
 * Brave Search APIキー等project-service/ai-serviceが所有するデータもまとめて扱っているが、
 * analytics_credentialsテーブル自体はanalytics-serviceが所有するため、Google Analytics/AdSense
 * 部分だけanalytics-serviceへのブリッジ経由にする(ai-service(#574)のAiProjectSettingsClientと
 * 同じ方式。呼び出し元ユーザーのBearerトークンをそのまま転送する)。
 *
 * <p>入力値検証(サービスアカウントJSON形式等)はanalytics-service側(InternalAnalyticsProjectSettings
 * Controller)で行い、その結果(4xx、{@link SyncServiceClientErrorException}）はそのまま
 * IllegalArgumentException(呼び出し元コントローラーの既存の@Validと同じ409マッピング)として
 * 再送出する(明確なエラー)。5xx・通信断・サーキットオープンは{@link AnalyticsServiceException}
 * (502マッピング)として区別する。
 */
@Component
public class AnalyticsProjectSettingsClient {

    private final SyncServiceClient client;
    private final HttpServletRequest request;

    public AnalyticsProjectSettingsClient(
            RestClient.Builder builder, @Value("${app.analytics-service-uri}") String analyticsServiceUri,
            HttpServletRequest request) {
        this.client = SyncServiceClient.builder(builder, "analytics-service", analyticsServiceUri)
                .profile(SyncCallProfile.STANDARD)
                .build();
        this.request = request;
    }

    // ---- Google Analytics ----

    public record GoogleAnalyticsStatus(boolean configured, String propertyId) {
    }

    public GoogleAnalyticsStatus getGoogleAnalyticsStatus(Long projectId) {
        GoogleAnalyticsStatus status = get(
                "/api/internal/analytics/projects/{projectId}/google-analytics", projectId, GoogleAnalyticsStatus.class);
        return status == null ? new GoogleAnalyticsStatus(false, null) : status;
    }

    public void setGoogleAnalyticsCredentials(Long projectId, String propertyId, String serviceAccountJson) {
        put("/api/internal/analytics/projects/{projectId}/google-analytics", projectId,
                Map.of("propertyId", propertyId, "serviceAccountJson", serviceAccountJson));
    }

    public void clearGoogleAnalyticsCredentials(Long projectId) {
        delete("/api/internal/analytics/projects/{projectId}/google-analytics", projectId);
    }

    // ---- AdSense ----

    public record AdSenseStatus(boolean configured, String accountId, String clientId, boolean hasClientSecret) {
    }

    public AdSenseStatus getAdSenseStatus(Long projectId) {
        AdSenseStatus status =
                get("/api/internal/analytics/projects/{projectId}/adsense", projectId, AdSenseStatus.class);
        return status == null ? new AdSenseStatus(false, null, null, false) : status;
    }

    public void setAdSenseSettings(Long projectId, String accountId, String clientId) {
        put("/api/internal/analytics/projects/{projectId}/adsense", projectId,
                Map.of("accountId", accountId, "clientId", clientId == null ? "" : clientId));
    }

    public void setAdSenseClientSecret(Long projectId, String clientSecret) {
        put("/api/internal/analytics/projects/{projectId}/adsense/client-secret", projectId,
                Map.of("clientSecret", clientSecret));
    }

    public void clearAdSenseCredentials(Long projectId) {
        delete("/api/internal/analytics/projects/{projectId}/adsense", projectId);
    }

    public void completeAdSenseOAuth(Long projectId, String code, String redirectUri) {
        try {
            client.postNoBody(
                    "/api/internal/analytics/projects/{projectId}/adsense/oauth-callback", new Object[] {projectId},
                    Map.of("code", code, "redirectUri", redirectUri), ServiceAuthHeaders.forwardedBearer(request));
        } catch (SyncServiceException e) {
            throw translate(e);
        }
    }

    private <T> T get(String uriTemplate, Long projectId, Class<T> type) {
        try {
            return client.get(uriTemplate, new Object[] {projectId}, type, ServiceAuthHeaders.forwardedBearer(request));
        } catch (SyncServiceException e) {
            throw translate(e);
        }
    }

    private void put(String uriTemplate, Long projectId, Map<String, String> body) {
        try {
            client.put(uriTemplate, new Object[] {projectId}, body, ServiceAuthHeaders.forwardedBearer(request));
        } catch (SyncServiceException e) {
            throw translate(e);
        }
    }

    private void delete(String uriTemplate, Long projectId) {
        try {
            client.delete(uriTemplate, new Object[] {projectId}, ServiceAuthHeaders.forwardedBearer(request));
        } catch (SyncServiceException e) {
            throw translate(e);
        }
    }

    /** 4xx(analytics-service側の入力値検証等)はIllegalArgumentExceptionとして呼び出し元へ再送出する。 */
    private RuntimeException translate(SyncServiceException e) {
        if (e instanceof SyncServiceClientErrorException clientError) {
            return new IllegalArgumentException(clientError.responseBody());
        }
        return new AnalyticsServiceException("analytics-serviceの呼び出しに失敗しました: " + e.getMessage(), e);
    }
}
