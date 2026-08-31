package com.letsblog.analytics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.analytics.adsense.AdSenseClient;
import com.letsblog.analytics.adsense.GoogleOAuthTokens;
import com.letsblog.analytics.analytics.GoogleServiceAccountKey;
import com.letsblog.common.crypto.CredentialCipher;
import org.springframework.stereotype.Service;

/**
 * プロジェクト単位の Google Analytics / AdSense 資格情報の設定操作。
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
    private final CredentialCipher credentialCipher;
    private final ObjectMapper objectMapper;

    public ProjectAnalyticsSettingsService(
            AnalyticsCredentialsService analyticsCredentialsService,
            AdSenseClient adSenseClient,
            CredentialCipher credentialCipher,
            ObjectMapper objectMapper) {
        this.analyticsCredentialsService = analyticsCredentialsService;
        this.adSenseClient = adSenseClient;
        this.credentialCipher = credentialCipher;
        this.objectMapper = objectMapper;
    }

    public boolean hasGoogleAnalytics(Long projectId) {
        return analyticsCredentialsService.hasGoogleAnalyticsCredentials(projectId);
    }

    public String googleAnalyticsPropertyId(Long projectId) {
        return analyticsCredentialsService.getGaPropertyId(projectId);
    }

    public void setGoogleAnalyticsCredentials(Long projectId, String propertyId, String serviceAccountJson) {
        validateGoogleServiceAccountJson(serviceAccountJson);
        analyticsCredentialsService.setGoogleAnalyticsCredentials(
                projectId, propertyId, credentialCipher.encrypt(serviceAccountJson));
    }

    public void clearGoogleAnalyticsCredentials(Long projectId) {
        analyticsCredentialsService.clearGoogleAnalyticsCredentials(projectId);
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
}
