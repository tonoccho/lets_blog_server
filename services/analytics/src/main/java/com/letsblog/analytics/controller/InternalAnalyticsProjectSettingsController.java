package com.letsblog.analytics.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.analytics.adsense.AdSenseClient;
import com.letsblog.analytics.adsense.GoogleOAuthTokens;
import com.letsblog.analytics.analytics.GoogleServiceAccountKey;
import com.letsblog.analytics.service.AnalyticsCredentialsService;
import com.letsblog.common.crypto.CredentialCipher;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * legacy-apiのProjectApiKeyController(Web管理画面向け、プロジェクト単位のAPIキー設定)が
 * analytics_credentials(analytics-serviceが所有、issue #571/#578)のGoogle Analytics/AdSense部分を
 * 読み書きするための内部ブリッジ(issue #578)。
 *
 * <p>ProjectApiKeyControllerはGitHubトークン/Brave Search APIキー等、project-service/ai-serviceが
 * 所有するデータもまとめて扱う「god controller」であり、そのうちGoogle Analytics/AdSenseの部分だけが
 * analytics-serviceの所有データになった(ai-service(#574)のInternalProjectAiSettingsControllerと
 * 同じ方針、追加の認可チェックは行わない。legacy-api側のAdminAuthorizationService#
 * requireProjectMemberOrAdminを既に済ませている)。
 *
 * <p>サービスアカウントJSON/クライアントシークレットは平文のままこのエンドポイントへ渡され、暗号化は
 * ここ(呼び出し元のCredentialCipherの鍵と同じ、全サービス共通のAPP_ENCRYPTION_KEY)で行う。
 */
@RestController
public class InternalAnalyticsProjectSettingsController {

    private final AnalyticsCredentialsService analyticsCredentialsService;
    private final AdSenseClient adSenseClient;
    private final CredentialCipher credentialCipher;
    private final ObjectMapper objectMapper;

    public InternalAnalyticsProjectSettingsController(
            AnalyticsCredentialsService analyticsCredentialsService,
            AdSenseClient adSenseClient,
            CredentialCipher credentialCipher,
            ObjectMapper objectMapper) {
        this.analyticsCredentialsService = analyticsCredentialsService;
        this.adSenseClient = adSenseClient;
        this.credentialCipher = credentialCipher;
        this.objectMapper = objectMapper;
    }

    // ---- Google Analytics ----

    public record GoogleAnalyticsStatusResponse(boolean configured, String propertyId) {
    }

    @GetMapping("/api/internal/analytics/projects/{projectId}/google-analytics")
    public GoogleAnalyticsStatusResponse googleAnalyticsStatus(@PathVariable Long projectId) {
        return new GoogleAnalyticsStatusResponse(
                analyticsCredentialsService.hasGoogleAnalyticsCredentials(projectId),
                analyticsCredentialsService.getGaPropertyId(projectId));
    }

    public record SetGoogleAnalyticsCredentialsRequest(@NotBlank String propertyId, @NotBlank String serviceAccountJson) {
    }

    @PutMapping("/api/internal/analytics/projects/{projectId}/google-analytics")
    public void setGoogleAnalyticsCredentials(
            @PathVariable Long projectId, @RequestBody SetGoogleAnalyticsCredentialsRequest request) {
        validateGoogleServiceAccountJson(request.serviceAccountJson());
        analyticsCredentialsService.setGoogleAnalyticsCredentials(
                projectId, request.propertyId(), credentialCipher.encrypt(request.serviceAccountJson()));
    }

    @DeleteMapping("/api/internal/analytics/projects/{projectId}/google-analytics")
    public void clearGoogleAnalyticsCredentials(@PathVariable Long projectId) {
        analyticsCredentialsService.clearGoogleAnalyticsCredentials(projectId);
    }

    /** 保存前にJSONとして解析可能で、GA4 Data API呼び出しに必要な項目を含むことを確認する。 */
    private void validateGoogleServiceAccountJson(String serviceAccountJson) {
        GoogleServiceAccountKey key;
        try {
            key = objectMapper.readValue(serviceAccountJson, GoogleServiceAccountKey.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("サービスアカウントJSONの形式が正しくありません", e);
        }
        if (key.clientEmail() == null || key.clientEmail().isBlank()
                || key.privateKey() == null || key.privateKey().isBlank()) {
            throw new IllegalArgumentException("サービスアカウントJSONにclient_email/private_keyが含まれていません");
        }
    }

    // ---- AdSense ----

    public record AdSenseStatusResponse(boolean configured, String accountId, String clientId, boolean hasClientSecret) {
    }

    @GetMapping("/api/internal/analytics/projects/{projectId}/adsense")
    public AdSenseStatusResponse adSenseStatus(@PathVariable Long projectId) {
        return new AdSenseStatusResponse(
                analyticsCredentialsService.hasAdsenseCredentials(projectId),
                analyticsCredentialsService.getAdsenseAccountId(projectId),
                analyticsCredentialsService.getAdsenseOauthClientId(projectId),
                analyticsCredentialsService.hasAdsenseOauthClientSecret(projectId));
    }

    public record SetAdSenseSettingsRequest(@NotBlank String accountId, String clientId) {
    }

    @PutMapping("/api/internal/analytics/projects/{projectId}/adsense")
    public void setAdSenseSettings(@PathVariable Long projectId, @RequestBody SetAdSenseSettingsRequest request) {
        analyticsCredentialsService.setAdSenseSettings(projectId, request.accountId(), request.clientId());
    }

    public record SetAdSenseClientSecretRequest(@NotBlank String clientSecret) {
    }

    @PutMapping("/api/internal/analytics/projects/{projectId}/adsense/client-secret")
    public void setAdSenseClientSecret(@PathVariable Long projectId, @RequestBody SetAdSenseClientSecretRequest request) {
        analyticsCredentialsService.setAdSenseClientSecretEncrypted(
                projectId, credentialCipher.encrypt(request.clientSecret()));
    }

    @DeleteMapping("/api/internal/analytics/projects/{projectId}/adsense")
    public void clearAdSenseCredentials(@PathVariable Long projectId) {
        analyticsCredentialsService.clearAdSenseCredentials(projectId);
    }

    public record CompleteAdSenseOAuthRequest(@NotBlank String code, @NotBlank String redirectUri) {
    }

    /**
     * Next.js側のOAuthコールバックルート(/connect/adsense/callback)から、legacy-apiの
     * ProjectApiKeyController経由で呼ばれる、認可コード交換の完了通知。アカウントIDは別途
     * setAdSenseSettingsで設定済みの前提(OAuth同意自体はどのAdSenseアカウントかを教えてくれないため)。
     * クライアントID/シークレットはこのプロジェクトに保存されたものを使う。
     */
    @PostMapping("/api/internal/analytics/projects/{projectId}/adsense/oauth-callback")
    public void completeAdSenseOAuth(@PathVariable Long projectId, @RequestBody CompleteAdSenseOAuthRequest request) {
        String clientId = analyticsCredentialsService.getAdsenseOauthClientId(projectId);
        String clientSecret = analyticsCredentialsService.hasAdsenseOauthClientSecret(projectId)
                ? credentialCipher.decrypt(analyticsCredentialsService.getAdsenseOauthClientSecretEncrypted(projectId))
                : null;
        GoogleOAuthTokens tokens = adSenseClient.exchangeAuthorizationCode(
                clientId, clientSecret, request.code(), request.redirectUri());
        analyticsCredentialsService.setAdsenseRefreshTokenEncrypted(
                projectId, credentialCipher.encrypt(tokens.refreshToken()));
    }
}
