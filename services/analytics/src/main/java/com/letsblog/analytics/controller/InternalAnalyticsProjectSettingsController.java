package com.letsblog.analytics.controller;

import com.letsblog.analytics.service.AdminAuthorizationService;
import com.letsblog.analytics.service.ProjectAnalyticsSettingsService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * プロジェクト単位の Google Analytics / AdSense 資格情報を、他サービスから読み書きするための
 * 内部ブリッジ({@code analytics_credentials}はanalytics-serviceが所有、issue #571/#578)。
 *
 * <p>issue #583以前は、Web管理画面向けのlegacy-api {@code ProjectApiKeyController}が
 * このブリッジ経由でここへ到達していた。#583で資格情報のAPIを所有サービスごとに分割し、
 * analytics-service自身が公開エンドポイント({@link ProjectAnalyticsApiKeyController})を
 * 持つようになったため、<b>legacy-apiからの利用者は居なくなった</b>。
 * 他サービス(project-service等)がプロジェクト削除時の後始末等で使う可能性を残して維持している。
 *
 * <p>実処理は{@link ProjectAnalyticsSettingsService}が持ち、公開側と共有する。
 *
 * <p>クライアントシークレットは平文のままこのエンドポイントへ渡され、
 * 暗号化は{@code ProjectAnalyticsSettingsService}(全サービス共通のAPP_ENCRYPTION_KEY)で行う。
 *
 * <p>Google Analyticsはissue #1231でユーザーOAuthに一本化し、サービスアカウントJSONを保存する経路は廃止した
 * (GAの連携操作はOAuthが必要なため公開エンドポイント側のみで、ここは状態取得と解除だけを持つ)。
 */
@RestController
public class InternalAnalyticsProjectSettingsController {

    private final ProjectAnalyticsSettingsService projectAnalyticsSettingsService;
    private final AdminAuthorizationService adminAuthorizationService;

    public InternalAnalyticsProjectSettingsController(
            ProjectAnalyticsSettingsService projectAnalyticsSettingsService,
            AdminAuthorizationService adminAuthorizationService) {
        this.projectAnalyticsSettingsService = projectAnalyticsSettingsService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    // ---- Google Analytics ----

    public record GoogleAnalyticsStatusResponse(boolean configured, String propertyId) {
    }

    /**
     * 認可不要: gatewayのルート表に載っておらず外部から到達できない内部ブリッジで、呼び出し元の
     * サービスが既に認可を済ませている(issue #583)。
     */
    @GetMapping("/api/internal/analytics/projects/{projectId}/google-analytics")
    public GoogleAnalyticsStatusResponse googleAnalyticsStatus(@PathVariable Long projectId) {
        return new GoogleAnalyticsStatusResponse(
                projectAnalyticsSettingsService.hasGoogleAnalytics(projectId),
                projectAnalyticsSettingsService.googleAnalyticsPropertyId(projectId));
    }

    public record GoogleAnalyticsCredentialsResponse(
            boolean configured, String propertyId, String clientId, String clientSecret, String refreshToken) {

        /** 秘密がログや例外メッセージに出ないよう、{@code toString}では秘密を伏せる。 */
        @Override
        public String toString() {
            return "GoogleAnalyticsCredentialsResponse[configured=" + configured + ", propertyId=" + propertyId + "]";
        }
    }

    /**
     * 本番サイトのプラグインへ送るための、復号済みの GA4 認証情報(issue #1578。project-service だけが呼ぶ)。
     * 秘密を返すので、他のブリッジと違って呼び出し元ユーザーがプロジェクトのメンバーかadminであることを検査する。
     */
    @GetMapping("/api/internal/analytics/projects/{projectId}/google-analytics/credentials")
    public GoogleAnalyticsCredentialsResponse googleAnalyticsCredentials(@PathVariable Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        var credentials = projectAnalyticsSettingsService.googleAnalyticsCredentials(projectId);
        return new GoogleAnalyticsCredentialsResponse(credentials.configured(), credentials.propertyId(),
                credentials.clientId(), credentials.clientSecret(), credentials.refreshToken());
    }

    /** 認可不要: {@link #googleAnalyticsStatus}と同じ理由(gateway非経由・呼び出し元が認可済み、issue #583)。 */
    @DeleteMapping("/api/internal/analytics/projects/{projectId}/google-analytics")
    public void clearGoogleAnalyticsCredentials(@PathVariable Long projectId) {
        projectAnalyticsSettingsService.clearGoogleAnalyticsCredentials(projectId);
    }

    // ---- AdSense ----

    public record AdSenseStatusResponse(
            boolean configured, String accountId, String clientId, boolean hasClientSecret) {
    }

    /** 認可不要: {@link #googleAnalyticsStatus}と同じ理由(gateway非経由・呼び出し元が認可済み、issue #583)。 */
    @GetMapping("/api/internal/analytics/projects/{projectId}/adsense")
    public AdSenseStatusResponse adSenseStatus(@PathVariable Long projectId) {
        return new AdSenseStatusResponse(
                projectAnalyticsSettingsService.hasAdSense(projectId),
                projectAnalyticsSettingsService.adSenseAccountId(projectId),
                projectAnalyticsSettingsService.adSenseClientId(projectId),
                projectAnalyticsSettingsService.hasAdSenseClientSecret(projectId));
    }

    public record SetAdSenseSettingsRequest(@NotBlank String accountId, String clientId) {
    }

    /** 認可不要: {@link #googleAnalyticsStatus}と同じ理由(gateway非経由・呼び出し元が認可済み、issue #583)。 */
    @PutMapping("/api/internal/analytics/projects/{projectId}/adsense")
    public void setAdSenseSettings(@PathVariable Long projectId, @RequestBody SetAdSenseSettingsRequest request) {
        projectAnalyticsSettingsService.setAdSenseSettings(projectId, request.accountId(), request.clientId());
    }

    public record SetAdSenseClientSecretRequest(@NotBlank String clientSecret) {
    }

    /** 認可不要: {@link #googleAnalyticsStatus}と同じ理由(gateway非経由・呼び出し元が認可済み、issue #583)。 */
    @PutMapping("/api/internal/analytics/projects/{projectId}/adsense/client-secret")
    public void setAdSenseClientSecret(
            @PathVariable Long projectId, @RequestBody SetAdSenseClientSecretRequest request) {
        projectAnalyticsSettingsService.setAdSenseClientSecret(projectId, request.clientSecret());
    }

    /** 認可不要: {@link #googleAnalyticsStatus}と同じ理由(gateway非経由・呼び出し元が認可済み、issue #583)。 */
    @DeleteMapping("/api/internal/analytics/projects/{projectId}/adsense")
    public void clearAdSenseCredentials(@PathVariable Long projectId) {
        projectAnalyticsSettingsService.clearAdSenseCredentials(projectId);
    }

    public record CompleteAdSenseOAuthRequest(@NotBlank String code, @NotBlank String redirectUri) {
    }

    /** 認可不要: {@link #googleAnalyticsStatus}と同じ理由(gateway非経由・呼び出し元が認可済み、issue #583)。 */
    @PostMapping("/api/internal/analytics/projects/{projectId}/adsense/oauth-callback")
    public void completeAdSenseOAuth(@PathVariable Long projectId, @RequestBody CompleteAdSenseOAuthRequest request) {
        projectAnalyticsSettingsService.completeAdSenseOAuth(projectId, request.code(), request.redirectUri());
    }
}
