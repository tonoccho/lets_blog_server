package com.letsblog.analytics.service;

import com.letsblog.analytics.adsense.AdSenseAccountSummary;
import com.letsblog.analytics.adsense.AdSenseClient;
import com.letsblog.analytics.adsense.AdSenseException;
import com.letsblog.analytics.adsense.GoogleOAuthTokens;
import com.letsblog.analytics.analytics.GoogleAnalyticsClient;
import com.letsblog.analytics.analytics.GoogleAnalyticsPropertySummary;
import com.letsblog.common.crypto.CredentialCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ProjectAnalyticsSettingsService.class);

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

    /**
     * 本番サイトのプラグインへ送る GA4 の認証情報(issue #1578)。復号済みの秘密を含むので、呼び出し元は
     * 内部ブリッジ({@code InternalAnalyticsProjectSettingsController})だけで、公開 API には載せない。
     * プロパティ選択・OAuthクライアント(ID とシークレット)・リフレッシュトークンが揃っていなければ
     * {@code configured=false}で、秘密は返さない。
     */
    public GoogleAnalyticsCredentialsView googleAnalyticsCredentials(Long projectId) {
        if (!analyticsCredentialsService.hasGoogleAnalyticsCredentials(projectId)) {
            return GoogleAnalyticsCredentialsView.UNCONFIGURED;
        }
        String clientId = analyticsCredentialsService.getGaOauthClientId(projectId);
        String clientSecret = decryptGaClientSecret(projectId);
        if (clientId == null || clientId.isBlank() || clientSecret == null) {
            return GoogleAnalyticsCredentialsView.UNCONFIGURED;
        }
        return new GoogleAnalyticsCredentialsView(
                true,
                analyticsCredentialsService.getGaPropertyId(projectId),
                clientId,
                clientSecret,
                credentialCipher.decrypt(analyticsCredentialsService.getGaRefreshTokenEncrypted(projectId)));
    }

    /** {@link #googleAnalyticsCredentials}の戻り値。{@code toString}で秘密が出ないよう、秘密を伏せる。 */
    public record GoogleAnalyticsCredentialsView(
            boolean configured, String propertyId, String clientId, String clientSecret, String refreshToken) {

        static final GoogleAnalyticsCredentialsView UNCONFIGURED =
                new GoogleAnalyticsCredentialsView(false, null, null, null, null);

        @Override
        public String toString() {
            return "GoogleAnalyticsCredentialsView[configured=" + configured + ", propertyId=" + propertyId + "]";
        }
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

    /** Googleアカウントとの連携(リフレッシュトークンの保存)が済んでいるか。パブリッシャーIDの有無は問わない。 */
    public boolean isAdSenseConnected(Long projectId) {
        return analyticsCredentialsService.hasAdsenseRefreshToken(projectId);
    }

    /** パブリッシャーIDは任意入力(issue #1232)。空(null/空白)はnullとして保存する。 */
    public void setAdSenseSettings(Long projectId, String accountId, String clientId) {
        String normalized = (accountId == null || accountId.isBlank()) ? null : accountId.trim();
        analyticsCredentialsService.setAdSenseSettings(projectId, normalized, clientId);
    }

    public void setAdSenseClientSecret(Long projectId, String clientSecret) {
        analyticsCredentialsService.setAdSenseClientSecretEncrypted(projectId, credentialCipher.encrypt(clientSecret));
    }

    public void clearAdSenseCredentials(Long projectId) {
        analyticsCredentialsService.clearAdSenseCredentials(projectId);
    }

    /**
     * AdSenseのOAuth認可コードをリフレッシュトークンへ交換して保存する。
     * クライアントID/シークレットはこのプロジェクトに保存されたものを使う。
     *
     * <p>issue #1232: OAuth同意自体はどのAdSenseアカウントかを教えてくれないが、同意直後のアクセストークンで
     * accounts.listを1回呼べば分かる。利用できるアカウントが1件ならそのパブリッシャーIDを保存する
     * (既存の値があっても、連携したアカウントが到達できるものの方が正しいので上書きする)。
     * 複数件・0件・取得失敗のときはIDを保存しない。取得失敗でもリフレッシュトークンは保存したままにし、
     * 理由は設定画面のアカウント一覧({@link #listAdSenseAccounts})が示す(手入力でも復旧できる)。
     */
    public void completeAdSenseOAuth(Long projectId, String code, String redirectUri) {
        GoogleOAuthTokens tokens = adSenseClient.exchangeAuthorizationCode(
                analyticsCredentialsService.getAdsenseOauthClientId(projectId),
                decryptAdSenseClientSecret(projectId),
                code,
                redirectUri);
        analyticsCredentialsService.setAdsenseRefreshTokenEncrypted(
                projectId, credentialCipher.encrypt(tokens.refreshToken()));
        try {
            List<AdSenseAccountSummary> accounts = adSenseClient.listAccounts(tokens.accessToken());
            if (accounts.size() == 1) {
                analyticsCredentialsService.setAdsenseAccountId(projectId, accounts.get(0).accountId());
            }
        } catch (AdSenseException e) {
            log.warn("AdSenseアカウント一覧の取得に失敗したためパブリッシャーIDの自動取得を見送ります(projectId={}): {}",
                    projectId, e.getMessage());
        }
    }

    /** 連携したGoogleアカウントが利用できるAdSenseアカウントの一覧(設定画面の選択肢)。 */
    public List<AdSenseAccountSummary> listAdSenseAccounts(Long projectId) {
        requireAdSenseConnected(projectId);
        String accessToken = adSenseClient.refreshAccessToken(
                analyticsCredentialsService.getAdsenseOauthClientId(projectId),
                decryptAdSenseClientSecret(projectId),
                credentialCipher.decrypt(analyticsCredentialsService.getAdsenseRefreshTokenEncrypted(projectId)));
        return adSenseClient.listAccounts(accessToken);
    }

    /** ダッシュボードで使うAdSenseアカウントを選択して保存する。"accounts/pub-XXXX"形式も受け付ける。 */
    public void selectAdSenseAccount(Long projectId, String accountId) {
        requireAdSenseConnected(projectId);
        String normalized = accountId == null ? "" : accountId.trim().replaceFirst("^accounts/", "");
        if (!normalized.matches("pub-\\d+")) {
            throw new IllegalArgumentException("AdSenseパブリッシャーIDは pub-1234567890123456 の形式で指定してください");
        }
        analyticsCredentialsService.setAdsenseAccountId(projectId, normalized);
    }

    private void requireAdSenseConnected(Long projectId) {
        if (!analyticsCredentialsService.hasAdsenseRefreshToken(projectId)) {
            throw new IllegalArgumentException("Googleアカウントと連携していません。先にGoogleアカウントと連携してください");
        }
    }

    private String decryptAdSenseClientSecret(Long projectId) {
        return analyticsCredentialsService.hasAdsenseOauthClientSecret(projectId)
                ? credentialCipher.decrypt(analyticsCredentialsService.getAdsenseOauthClientSecretEncrypted(projectId))
                : null;
    }
}
