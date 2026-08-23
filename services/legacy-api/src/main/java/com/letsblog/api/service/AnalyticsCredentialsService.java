package com.letsblog.api.service;

import com.letsblog.api.domain.AnalyticsCredentials;
import com.letsblog.api.repository.AnalyticsCredentialsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * プロジェクト単位のGoogle Analytics/AdSense連携設定(analytics_credentials)の読み書きを扱う(issue #571)。
 * projects god-tableの分割で切り出された設定テーブルで、行は初回書き込み時に遅延作成する
 * (未設定のプロジェクトに空行を作らないため)。認可(プロジェクトメンバー/admin判定)は
 * 呼び出し元(ProjectApiKeyService等)が担う。
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
    public byte[] getGaServiceAccountJsonEncrypted(Long projectId) {
        return findByProjectId(projectId).map(AnalyticsCredentials::getGaServiceAccountJsonEncrypted).orElse(null);
    }

    @Transactional
    public void setGoogleAnalyticsCredentials(Long projectId, String propertyId, byte[] serviceAccountJsonEncrypted) {
        AnalyticsCredentials credentials = getOrCreate(projectId);
        credentials.setGaPropertyId(propertyId);
        credentials.setGaServiceAccountJsonEncrypted(serviceAccountJsonEncrypted);
        repository.save(credentials);
    }

    @Transactional
    public void clearGoogleAnalyticsCredentials(Long projectId) {
        setGoogleAnalyticsCredentials(projectId, null, null);
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
