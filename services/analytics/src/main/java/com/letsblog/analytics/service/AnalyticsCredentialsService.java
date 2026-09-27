package com.letsblog.analytics.service;

import com.letsblog.analytics.domain.AnalyticsCredentials;
import com.letsblog.analytics.repository.AnalyticsCredentialsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * プロジェクト単位のGoogle Analytics/AdSense連携設定(analytics_credentials)の読み書きを扱う
 * (issue #571でlegacy-apiのprojects god-tableから分離、issue #578でanalytics-serviceへ物理移管)。
 * 行は初回書き込み時に遅延作成する(未設定のプロジェクトに空行を作らないため)。認可
 * (プロジェクトメンバー/admin判定)は呼び出し元(InternalAnalyticsProjectSettingsController等)が担う。
 */
@Service
public class AnalyticsCredentialsService {

    private final AnalyticsCredentialsRepository repository;

    public AnalyticsCredentialsService(AnalyticsCredentialsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<AnalyticsCredentials> findByProjectId(Long projectId) {
        return repository.findByProjectId(projectId);
    }

    @Transactional
    public AnalyticsCredentials getOrCreate(Long projectId) {
        return repository.findByProjectId(projectId)
                .orElseGet(() -> repository.save(new AnalyticsCredentials(projectId)));
    }

    // ---- Google Analytics ----

    @Transactional(readOnly = true)
    public boolean hasGoogleAnalyticsCredentials(Long projectId) {
        return findByProjectId(projectId).map(AnalyticsCredentials::hasGoogleAnalyticsCredentials).orElse(false);
    }

    @Transactional(readOnly = true)
    public String getGaPropertyId(Long projectId) {
        return findByProjectId(projectId).map(AnalyticsCredentials::getGaPropertyId).orElse(null);
    }

    @Transactional(readOnly = true)
    public boolean hasGaRefreshToken(Long projectId) {
        return findByProjectId(projectId).map(AnalyticsCredentials::hasGoogleAnalyticsConnection).orElse(false);
    }

    @Transactional(readOnly = true)
    public String getGaOauthClientId(Long projectId) {
        return findByProjectId(projectId).map(AnalyticsCredentials::getGaOauthClientId).orElse(null);
    }

    @Transactional(readOnly = true)
    public boolean hasGaOauthClientSecret(Long projectId) {
        return findByProjectId(projectId)
                .map(c -> c.getGaOauthClientSecretEncrypted() != null && c.getGaOauthClientSecretEncrypted().length > 0)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public byte[] getGaOauthClientSecretEncrypted(Long projectId) {
        return findByProjectId(projectId).map(AnalyticsCredentials::getGaOauthClientSecretEncrypted).orElse(null);
    }

    @Transactional(readOnly = true)
    public byte[] getGaRefreshTokenEncrypted(Long projectId) {
        return findByProjectId(projectId).map(AnalyticsCredentials::getGaRefreshTokenEncrypted).orElse(null);
    }

    /** GA用OAuthクライアントIDを保存する。シークレットはnullなら既存の値を変更しない。 */
    @Transactional
    public void setGaOauthClient(Long projectId, String clientId, byte[] clientSecretEncrypted) {
        AnalyticsCredentials credentials = getOrCreate(projectId);
        credentials.setGaOauthClientId(clientId);
        if (clientSecretEncrypted != null) {
            credentials.setGaOauthClientSecretEncrypted(clientSecretEncrypted);
        }
        repository.save(credentials);
    }

    @Transactional
    public void setGaRefreshTokenEncrypted(Long projectId, byte[] refreshTokenEncrypted) {
        AnalyticsCredentials credentials = getOrCreate(projectId);
        credentials.setGaRefreshTokenEncrypted(refreshTokenEncrypted);
        repository.save(credentials);
    }

    @Transactional
    public void setGaPropertyId(Long projectId, String propertyId) {
        AnalyticsCredentials credentials = getOrCreate(projectId);
        credentials.setGaPropertyId(propertyId);
        repository.save(credentials);
    }

    /** 連携を解除する。リフレッシュトークン・選択済みプロパティ・OAuthクライアントをすべて破棄する。 */
    @Transactional
    public void clearGoogleAnalyticsCredentials(Long projectId) {
        AnalyticsCredentials credentials = getOrCreate(projectId);
        credentials.setGaPropertyId(null);
        credentials.setGaRefreshTokenEncrypted(null);
        credentials.setGaOauthClientId(null);
        credentials.setGaOauthClientSecretEncrypted(null);
        repository.save(credentials);
    }

    // ---- AdSense ----

    @Transactional(readOnly = true)
    public boolean hasAdsenseCredentials(Long projectId) {
        return findByProjectId(projectId).map(AnalyticsCredentials::hasAdsenseCredentials).orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean hasAdsenseOauthClientSecret(Long projectId) {
        return findByProjectId(projectId).map(AnalyticsCredentials::hasAdsenseOauthClientSecret).orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean hasAdsenseOauthClient(Long projectId) {
        return findByProjectId(projectId).map(AnalyticsCredentials::hasAdsenseOauthClient).orElse(false);
    }

    @Transactional(readOnly = true)
    public String getAdsenseAccountId(Long projectId) {
        return findByProjectId(projectId).map(AnalyticsCredentials::getAdsenseAccountId).orElse(null);
    }

    @Transactional(readOnly = true)
    public String getAdsenseOauthClientId(Long projectId) {
        return findByProjectId(projectId).map(AnalyticsCredentials::getAdsenseOauthClientId).orElse(null);
    }

    @Transactional(readOnly = true)
    public byte[] getAdsenseRefreshTokenEncrypted(Long projectId) {
        return findByProjectId(projectId).map(AnalyticsCredentials::getAdsenseRefreshTokenEncrypted).orElse(null);
    }

    @Transactional(readOnly = true)
    public byte[] getAdsenseOauthClientSecretEncrypted(Long projectId) {
        return findByProjectId(projectId).map(AnalyticsCredentials::getAdsenseOauthClientSecretEncrypted).orElse(null);
    }

    /** AdSenseパブリッシャーIDとGoogle OAuthクライアントID(秘匿情報ではない)をまとめて保存する。 */
    @Transactional
    public void setAdSenseSettings(Long projectId, String accountId, String clientId) {
        AnalyticsCredentials credentials = getOrCreate(projectId);
        credentials.setAdsenseAccountId(accountId);
        credentials.setAdsenseOauthClientId(clientId);
        repository.save(credentials);
    }

    @Transactional
    public void setAdSenseClientSecretEncrypted(Long projectId, byte[] clientSecretEncrypted) {
        AnalyticsCredentials credentials = getOrCreate(projectId);
        credentials.setAdsenseOauthClientSecretEncrypted(clientSecretEncrypted);
        repository.save(credentials);
    }

    @Transactional
    public void setAdsenseRefreshTokenEncrypted(Long projectId, byte[] refreshTokenEncrypted) {
        AnalyticsCredentials credentials = getOrCreate(projectId);
        credentials.setAdsenseRefreshTokenEncrypted(refreshTokenEncrypted);
        repository.save(credentials);
    }

    @Transactional
    public void clearAdSenseCredentials(Long projectId) {
        AnalyticsCredentials credentials = getOrCreate(projectId);
        credentials.setAdsenseAccountId(null);
        credentials.setAdsenseRefreshTokenEncrypted(null);
        credentials.setAdsenseOauthClientId(null);
        credentials.setAdsenseOauthClientSecretEncrypted(null);
        repository.save(credentials);
    }
}
