package com.letsblog.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.adsense.AdSenseClient;
import com.letsblog.api.adsense.GoogleOAuthTokens;
import com.letsblog.api.analytics.GoogleServiceAccountKey;
import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.api.domain.Project;
import com.letsblog.api.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * プロジェクト単位のGitHubトークン/Brave Search APIキー/Google Analytics/AdSense連携情報を管理する(issue #184)。
 * プロジェクトに値が設定されていればそれを優先し、未設定の場合は
 * GitHubトークンは操作者本人のユーザー設定(UserService)、Brave Search APIキーは
 * システム全体設定(SystemSettingService)へフォールバックする(既存の動作を壊さないため)。
 * projects god-tableの分割(issue #571)により、GitHubトークンはprojects自体、Brave Search APIキーは
 * project_ai_settings(ProjectAiSettingsService)、GA/AdSenseはanalytics_credentials(AnalyticsCredentialsService)
 * にそれぞれ保持する。
 */
@Service
public class ProjectApiKeyService {

    private final ProjectRepository projectRepository;
    private final ProjectAiSettingsService projectAiSettingsService;
    private final AnalyticsCredentialsService analyticsCredentialsService;
    private final CredentialCipher credentialCipher;
    private final UserService userService;
    private final SystemSettingService systemSettingService;
    private final AdminAuthorizationService adminAuthorizationService;
    private final ObjectMapper objectMapper;
    private final AdSenseClient adSenseClient;

    public ProjectApiKeyService(
            ProjectRepository projectRepository,
            ProjectAiSettingsService projectAiSettingsService,
            AnalyticsCredentialsService analyticsCredentialsService,
            CredentialCipher credentialCipher,
            UserService userService,
            SystemSettingService systemSettingService,
            AdminAuthorizationService adminAuthorizationService,
            ObjectMapper objectMapper,
            AdSenseClient adSenseClient) {
        this.projectRepository = projectRepository;
        this.projectAiSettingsService = projectAiSettingsService;
        this.analyticsCredentialsService = analyticsCredentialsService;
        this.credentialCipher = credentialCipher;
        this.userService = userService;
        this.systemSettingService = systemSettingService;
        this.adminAuthorizationService = adminAuthorizationService;
        this.objectMapper = objectMapper;
        this.adSenseClient = adSenseClient;
    }

    @Transactional(readOnly = true)
    public boolean isGithubTokenConfigured(Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return getProject(projectId).hasGithubToken();
    }

    @Transactional(readOnly = true)
    public boolean isBraveSearchApiKeyConfigured(Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        requireProjectExists(projectId);
        return projectAiSettingsService.hasBraveSearchApiKey(projectId);
    }

    @Transactional
    public void setGithubToken(Long projectId, String token) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        Project project = getProject(projectId);
        project.setGithubTokenEncrypted(credentialCipher.encrypt(token));
        projectRepository.save(project);
    }

    @Transactional
    public void clearGithubToken(Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        Project project = getProject(projectId);
        project.setGithubTokenEncrypted(null);
        projectRepository.save(project);
    }

    @Transactional
    public void setBraveSearchApiKey(Long projectId, String apiKey) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        requireProjectExists(projectId);
        projectAiSettingsService.setBraveSearchApiKeyEncrypted(projectId, credentialCipher.encrypt(apiKey));
    }

    @Transactional
    public void clearBraveSearchApiKey(Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        requireProjectExists(projectId);
        projectAiSettingsService.setBraveSearchApiKeyEncrypted(projectId, null);
    }

    @Transactional(readOnly = true)
    public boolean isGoogleAnalyticsConfigured(Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        requireProjectExists(projectId);
        return analyticsCredentialsService.hasGoogleAnalyticsCredentials(projectId);
    }

    @Transactional(readOnly = true)
    public String getGoogleAnalyticsPropertyId(Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        requireProjectExists(projectId);
        return analyticsCredentialsService.getGaPropertyId(projectId);
    }

    @Transactional
    public void setGoogleAnalyticsCredentials(Long projectId, String propertyId, String serviceAccountJson) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        validateGoogleServiceAccountJson(serviceAccountJson);
        requireProjectExists(projectId);
        analyticsCredentialsService.setGoogleAnalyticsCredentials(
                projectId, propertyId, credentialCipher.encrypt(serviceAccountJson));
    }

    @Transactional
    public void clearGoogleAnalyticsCredentials(Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        requireProjectExists(projectId);
        analyticsCredentialsService.clearGoogleAnalyticsCredentials(projectId);
    }

    /**
     * GoogleAnalyticsReportServiceから呼ばれる。GA未設定の場合はnullを返す(認可はここでは行わない。
     * 呼び出し元がプロジェクトメンバー/adminであることを別途保証している。resolveGithubToken等と同じ方針)。
     */
    @Transactional(readOnly = true)
    public GoogleServiceAccountKey resolveGoogleAnalyticsServiceAccountKey(Long projectId) {
        requireProjectExists(projectId);
        if (!analyticsCredentialsService.hasGoogleAnalyticsCredentials(projectId)) {
            return null;
        }
        String json = credentialCipher.decrypt(analyticsCredentialsService.getGaServiceAccountJsonEncrypted(projectId));
        try {
            return objectMapper.readValue(json, GoogleServiceAccountKey.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("保存済みのサービスアカウントJSONの解析に失敗しました", e);
        }
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

    public record AdSenseStatus(boolean configured, String accountId, String clientId, boolean hasClientSecret) {
    }

    @Transactional(readOnly = true)
    public AdSenseStatus getAdSenseStatus(Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        requireProjectExists(projectId);
        return new AdSenseStatus(
                analyticsCredentialsService.hasAdsenseCredentials(projectId),
                analyticsCredentialsService.getAdsenseAccountId(projectId),
                analyticsCredentialsService.getAdsenseOauthClientId(projectId),
                analyticsCredentialsService.hasAdsenseOauthClientSecret(projectId));
    }

    /** AdSenseパブリッシャーIDとGoogle OAuthクライアントID(秘匿情報ではない)をまとめて保存する。 */
    @Transactional
    public void setAdSenseSettings(Long projectId, String accountId, String clientId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        requireProjectExists(projectId);
        analyticsCredentialsService.setAdSenseSettings(projectId, accountId, clientId);
    }

    @Transactional
    public void setAdSenseClientSecret(Long projectId, String clientSecret) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        requireProjectExists(projectId);
        analyticsCredentialsService.setAdSenseClientSecretEncrypted(projectId, credentialCipher.encrypt(clientSecret));
    }

    /**
     * Next.js側のOAuthコールバックルート(/connect/adsense/callback)から呼ばれる。認可コードを
     * リフレッシュトークンに交換して暗号化保存する(アカウントIDは別途setAdSenseSettingsで設定済みの前提。
     * OAuth同意自体はどのAdSenseアカウントかを教えてくれないため)。クライアントID/シークレットは
     * このプロジェクトに保存されたもの(issue #407でプロジェクト単位に変更)を使う。
     */
    @Transactional
    public void completeAdSenseOAuth(Long projectId, String code, String redirectUri) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        requireProjectExists(projectId);
        String clientSecret = analyticsCredentialsService.hasAdsenseOauthClientSecret(projectId)
                ? credentialCipher.decrypt(analyticsCredentialsService.getAdsenseOauthClientSecretEncrypted(projectId))
                : null;
        GoogleOAuthTokens tokens = adSenseClient.exchangeAuthorizationCode(
                analyticsCredentialsService.getAdsenseOauthClientId(projectId), clientSecret, code, redirectUri);
        analyticsCredentialsService.setAdsenseRefreshTokenEncrypted(projectId, credentialCipher.encrypt(tokens.refreshToken()));
    }

    @Transactional
    public void clearAdSenseCredentials(Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        requireProjectExists(projectId);
        analyticsCredentialsService.clearAdSenseCredentials(projectId);
    }

    /**
     * AdSenseReportServiceから呼ばれる。未設定の場合はnullを返す(認可はここでは行わない。
     * 呼び出し元がプロジェクトメンバー/adminであることを別途保証している。resolveGithubToken等と同じ方針)。
     */
    @Transactional(readOnly = true)
    public String resolveAdSenseRefreshToken(Long projectId) {
        requireProjectExists(projectId);
        if (!analyticsCredentialsService.hasAdsenseCredentials(projectId)) {
            return null;
        }
        return credentialCipher.decrypt(analyticsCredentialsService.getAdsenseRefreshTokenEncrypted(projectId));
    }

    /**
     * AdSenseReportServiceから呼ばれる。未設定の場合はnullを返す(認可はここでは行わない。
     * 呼び出し元がプロジェクトメンバー/adminであることを別途保証している。resolveGithubToken等と同じ方針)。
     */
    @Transactional(readOnly = true)
    public String resolveAdSenseOauthClientSecret(Long projectId) {
        requireProjectExists(projectId);
        if (!analyticsCredentialsService.hasAdsenseOauthClientSecret(projectId)) {
            return null;
        }
        return credentialCipher.decrypt(analyticsCredentialsService.getAdsenseOauthClientSecretEncrypted(projectId));
    }

    /**
     * ArticlePlanService(GitHub Issue連携)から呼ばれる。プロジェクトにトークンが設定されていれば
     * それを優先し、未設定なら操作者本人のユーザー設定へフォールバックする(認可はここでは行わない。
     * 呼び出し元がプロジェクトメンバー/adminであることを別途保証している)。
     */
    @Transactional(readOnly = true)
    public String resolveGithubToken(Long projectId, Long actorUserId) {
        Project project = getProject(projectId);
        if (project.hasGithubToken()) {
            return credentialCipher.decrypt(project.getGithubTokenEncrypted());
        }
        return userService.getDecryptedGithubToken(actorUserId);
    }

    /**
     * WebSearchServiceから呼ばれる。プロジェクトにキーが設定されていればそれを優先し、
     * 未設定ならシステム全体設定へフォールバックする。
     */
    @Transactional(readOnly = true)
    public String resolveBraveSearchApiKey(Long projectId) {
        requireProjectExists(projectId);
        if (projectAiSettingsService.hasBraveSearchApiKey(projectId)) {
            return credentialCipher.decrypt(projectAiSettingsService.getBraveSearchApiKeyEncrypted(projectId));
        }
        return systemSettingService.getBraveSearchApiKey();
    }

    private Project getProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException("id " + projectId + " のプロジェクトは登録されていません"));
    }

    private void requireProjectExists(Long projectId) {
        getProject(projectId);
    }
}
