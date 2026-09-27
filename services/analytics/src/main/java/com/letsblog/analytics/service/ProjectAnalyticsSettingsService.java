package com.letsblog.analytics.service;

import com.letsblog.analytics.adsense.AdSenseClient;
import com.letsblog.analytics.adsense.GoogleOAuthTokens;
import com.letsblog.analytics.analytics.GoogleAnalyticsClient;
import com.letsblog.analytics.analytics.GoogleAnalyticsPropertySummary;
import com.letsblog.common.crypto.CredentialCipher;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * プロジェクト単位の Google Analytics / AdSense 資格情報の設定操作。
 * GAはissue #1231でサービスアカウントJSONからAdSenseと同じユーザーOAuthへ移行した。
 *
 * <p>issue #583以前、この処理は{@code InternalAnalyticsProjectSettingsController}(内部ブリッジ)
 * にだけ存在し、Web管理画面からはlegacy-apiの{@code ProjectApiKeyController}が
 * そのブリッジを叩いて到達していた。#583で資格情報のAPIを所有サービスごとに分割し、
 * analytics-service自身が公開エンドポイント({@code ProjectAnalyticsApiKeyController})を
 * 持つようになったため、両方から使えるようコントローラから切り出した。
 *
 * <p>認可はこのクラスでは行わない。公開側は{@code requireProjectMemberOrAdmin}を、
 * 内部ブリッジ側は呼び出し元が済ませた認可を前提とする。
 */
@Service
public class ProjectAnalyticsSettingsService {

    private final AnalyticsCredentialsService analyticsCredentialsService;
    private final AdSenseClient adSenseClient;
    private final GoogleAnalyticsClient googleAnalyticsClient;
    private final CredentialCipher credentialCipher;

    public ProjectAnalyticsSettingsService(
            AnalyticsCredentialsService analyticsCredentialsService,
            AdSenseClient adSenseClient,
            GoogleAnalyticsClient googleAnalyticsClient,
            CredentialCipher credentialCipher) {
        this.analyticsCredentialsService = analyticsCredentialsService;
        this.adSenseClient = adSenseClient;
        this.googleAnalyticsClient = googleAnalyticsClient;
        this.credentialCipher = credentialCipher;
    }

    public boolean hasGoogleAnalytics(Long projectId) {
        return analyticsCredentialsService.hasGoogleAnalyticsCredentials(projectId);
    }

    public String googleAnalyticsPropertyId(Long projectId) {
        return analyticsCredentialsService.getGaPropertyId(projectId);
    }

    public String googleAnalyticsClientId(Long projectId) {
        return analyticsCredentialsService.getGaOauthClientId(projectId);
    }

    public boolean hasGoogleAnalyticsClientSecret(Long projectId) {
        return analyticsCredentialsService.hasGaOauthClientSecret(projectId);
    }

    /** Googleアカウントとの連携(リフレッシュトークンの保存)が済んでいるか。プロパティ選択の有無は問わない。 */
    public boolean isGoogleAnalyticsConnected(Long projectId) {
        return analyticsCredentialsService.hasGaRefreshToken(projectId);
    }

    /** GA用OAuthクライアントを保存する。シークレットが空(null/空白)なら既存の値を変更しない。 */
    public void setGoogleAnalyticsClient(Long projectId, String clientId, String clientSecret) {
        byte[] encryptedSecret =
                (clientSecret == null || clientSecret.isBlank()) ? null : credentialCipher.encrypt(clientSecret);
        analyticsCredentialsService.setGaOauthClient(projectId, clientId, encryptedSecret);
    }

    /**
     * GAのOAuth認可コードをリフレッシュトークンへ交換して暗号化保存する。
     * クライアントID/シークレットはこのプロジェクトに保存されたものを使う。
     */
    public void completeGoogleAnalyticsOAuth(Long projectId, String code, String redirectUri) {
        String clientId = analyticsCredentialsService.getGaOauthClientId(projectId);
        String clientSecret = decryptGaClientSecret(projectId);
        GoogleOAuthTokens tokens =
                googleAnalyticsClient.exchangeAuthorizationCode(clientId, clientSecret, code, redirectUri);
        analyticsCredentialsService.setGaRefreshTokenEncrypted(
                projectId, credentialCipher.encrypt(tokens.refreshToken()));
    }

    /** 連携したGoogleアカウントがアクセスできるGA4プロパティの一覧(設定画面の選択肢)。 */
    public List<GoogleAnalyticsPropertySummary> listGoogleAnalyticsProperties(Long projectId) {
        requireGoogleAnalyticsConnected(projectId);
        String accessToken = googleAnalyticsClient.refreshAccessToken(
                analyticsCredentialsService.getGaOauthClientId(projectId),
                decryptGaClientSecret(projectId),
                credentialCipher.decrypt(analyticsCredentialsService.getGaRefreshTokenEncrypted(projectId)));
        return googleAnalyticsClient.listProperties(accessToken);
    }

    /** ダッシュボードで使うGA4プロパティを選択して保存する。"properties/123"形式も受け付ける。 */
    public void selectGoogleAnalyticsProperty(Long projectId, String propertyId) {
        requireGoogleAnalyticsConnected(projectId);
        String normalized = propertyId == null ? "" : propertyId.trim().replaceFirst("^properties/", "");
        if (!normalized.matches("\\d+")) {
            throw new IllegalArgumentException("GA4プロパティIDは数字で指定してください");
        }
        analyticsCredentialsService.setGaPropertyId(projectId, normalized);
    }

    public void clearGoogleAnalyticsCredentials(Long projectId) {
        analyticsCredentialsService.clearGoogleAnalyticsCredentials(projectId);
    }

    private void requireGoogleAnalyticsConnected(Long projectId) {
        if (!analyticsCredentialsService.hasGaRefreshToken(projectId)) {
            throw new IllegalArgumentException("Googleアカウントと連携していません。先にGoogleアカウントと連携してください");
        }
    }

    private String decryptGaClientSecret(Long projectId) {
        return analyticsCredentialsService.hasGaOauthClientSecret(projectId)
                ? credentialCipher.decrypt(analyticsCredentialsService.getGaOauthClientSecretEncrypted(projectId))
                : null;
    }

    public boolean hasAdSense(Long projectId) {
        return analyticsCredentialsService.hasAdsenseCredentials(projectId);
    }

    public String adSenseAccountId(Long projectId) {
        return analyticsCredentialsService.getAdsenseAccountId(projectId);
    }

    public String adSenseClientId(Long projectId) {
        return analyticsCredentialsService.getAdsenseOauthClientId(projectId);
    }

    public boolean hasAdSenseClientSecret(Long projectId) {
        return analyticsCredentialsService.hasAdsenseOauthClientSecret(projectId);
    }

    public void setAdSenseSettings(Long projectId, String accountId, String clientId) {
        analyticsCredentialsService.setAdSenseSettings(projectId, accountId, clientId);
    }

    public void setAdSenseClientSecret(Long projectId, String clientSecret) {
        analyticsCredentialsService.setAdSenseClientSecretEncrypted(projectId, credentialCipher.encrypt(clientSecret));
    }

    public void clearAdSenseCredentials(Long projectId) {
        analyticsCredentialsService.clearAdSenseCredentials(projectId);
    }

    /**
     * AdSenseのOAuth認可コードをリフレッシュトークンへ交換して保存する。
     * アカウントIDは別途{@link #setAdSenseSettings}で設定済みの前提
     * (OAuth同意自体はどのAdSenseアカウントかを教えてくれないため)。
     * クライアントID/シークレットはこのプロジェクトに保存されたものを使う。
     */
    public void completeAdSenseOAuth(Long projectId, String code, String redirectUri) {
        String clientId = analyticsCredentialsService.getAdsenseOauthClientId(projectId);
        String clientSecret = analyticsCredentialsService.hasAdsenseOauthClientSecret(projectId)
                ? credentialCipher.decrypt(analyticsCredentialsService.getAdsenseOauthClientSecretEncrypted(projectId))
                : null;
        GoogleOAuthTokens tokens =
                adSenseClient.exchangeAuthorizationCode(clientId, clientSecret, code, redirectUri);
        analyticsCredentialsService.setAdsenseRefreshTokenEncrypted(
                projectId, credentialCipher.encrypt(tokens.refreshToken()));
    }
}
