package com.letsblog.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.client.AiProjectSettingsClient;
import com.letsblog.api.client.AnalyticsProjectSettingsClient;
import com.letsblog.api.client.ProjectServiceClient;
import com.letsblog.common.crypto.CredentialCipher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * プロジェクト単位のGitHubトークン/Brave Search APIキー/Google Analytics/AdSense連携情報を管理する(issue #184)。
 * プロジェクトに値が設定されていればそれを優先し、未設定の場合はGitHubトークンは操作者本人のユーザー設定
 * (UserService)へフォールバックする(既存の動作を壊さないため)。projects god-tableの分割
 * (issue #571)により、GitHubトークンはprojects自体、Brave Search APIキーはproject_ai_settings、
 * GA/AdSenseはanalytics_credentialsにそれぞれ保持する。project_ai_settingsはissue #574でai-serviceへ、
 * analytics_credentialsはissue #578でanalytics-serviceへそれぞれ移管されたため、Brave Search APIキー/
 * GA/AdSenseの読み書きはいずれも内部ブリッジ({@link AiProjectSettingsClient}/
 * {@link AnalyticsProjectSettingsClient})経由に委ねる。GitHubトークン(projects.github_token_encrypted)
 * の所有権はproject-serviceへ移った(issue #577 stage2)ため、{@link ProjectServiceClient}経由の内部
 * ブリッジで読み書きする(stage3で、legacy-apiローカルの{@code ProjectRepository}への直接アクセスを廃止した。
 * project-service側で新規作成されたプロジェクトはlegacy-apiのローカルprojectsテーブルに行を持たないため、
 * ローカル参照のままでは全ての新規プロジェクトでNotFoundになっていた欠陥の修正でもある)。
 */
@Service
public class ProjectApiKeyService {

    private final ProjectServiceClient projectServiceClient;
    private final AiProjectSettingsClient aiProjectSettingsClient;
    private final AnalyticsProjectSettingsClient analyticsProjectSettingsClient;
    private final CredentialCipher credentialCipher;
    private final UserService userService;
    private final AdminAuthorizationService adminAuthorizationService;
    private final ObjectMapper objectMapper;

    public ProjectApiKeyService(
            ProjectServiceClient projectServiceClient,
            AiProjectSettingsClient aiProjectSettingsClient,
            AnalyticsProjectSettingsClient analyticsProjectSettingsClient,
            CredentialCipher credentialCipher,
            UserService userService,
            AdminAuthorizationService adminAuthorizationService,
            ObjectMapper objectMapper) {
        this.projectServiceClient = projectServiceClient;
        this.aiProjectSettingsClient = aiProjectSettingsClient;
        this.analyticsProjectSettingsClient = analyticsProjectSettingsClient;
        this.credentialCipher = credentialCipher;
        this.userService = userService;
        this.adminAuthorizationService = adminAuthorizationService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public boolean isGithubTokenConfigured(Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return projectServiceClient.getGithubToken(projectId).configured();
    }

    @Transactional(readOnly = true)
    public boolean isBraveSearchApiKeyConfigured(Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        requireProjectExists(projectId);
        return aiProjectSettingsClient.isBraveSearchApiKeyConfigured(projectId);
    }

    @Transactional
    public void setGithubToken(Long projectId, String token) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        projectServiceClient.setGithubToken(projectId, credentialCipher.encrypt(token));
    }

    @Transactional
    public void clearGithubToken(Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        projectServiceClient.setGithubToken(projectId, null);
    }

    @Transactional
    public void setBraveSearchApiKey(Long projectId, String apiKey) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        requireProjectExists(projectId);
        aiProjectSettingsClient.setBraveSearchApiKey(projectId, apiKey);
    }

    @Transactional
    public void clearBraveSearchApiKey(Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        requireProjectExists(projectId);
        aiProjectSettingsClient.clearBraveSearchApiKey(projectId);
    }

    @Transactional(readOnly = true)
    public boolean isGoogleAnalyticsConfigured(Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        requireProjectExists(projectId);
        return analyticsProjectSettingsClient.getGoogleAnalyticsStatus(projectId).configured();
    }

    @Transactional(readOnly = true)
    public String getGoogleAnalyticsPropertyId(Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        requireProjectExists(projectId);
        return analyticsProjectSettingsClient.getGoogleAnalyticsStatus(projectId).propertyId();
    }

    @Transactional
    public void setGoogleAnalyticsCredentials(Long projectId, String propertyId, String serviceAccountJson) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        validateGoogleServiceAccountJson(serviceAccountJson);
        requireProjectExists(projectId);
        analyticsProjectSettingsClient.setGoogleAnalyticsCredentials(projectId, propertyId, serviceAccountJson);
    }

    @Transactional
    public void clearGoogleAnalyticsCredentials(Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        requireProjectExists(projectId);
        analyticsProjectSettingsClient.clearGoogleAnalyticsCredentials(projectId);
    }

    /**
     * 保存前にJSONとして解析可能で、GA4 Data API呼び出しに必要な項目を含むことを確認する
     * (analytics-service側(InternalAnalyticsProjectSettingsController)でも同じ検証を行うが、
     * 明らかに不正な入力は内部ブリッジ呼び出し前にここで弾く)。
     */
    private void validateGoogleServiceAccountJson(String serviceAccountJson) {
        JsonNode json;
        try {
            json = objectMapper.readTree(serviceAccountJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("サービスアカウントJSONの形式が正しくありません", e);
        }
        if (!json.hasNonNull("client_email") || json.path("client_email").asText().isBlank()
                || !json.hasNonNull("private_key") || json.path("private_key").asText().isBlank()) {
            throw new IllegalArgumentException("サービスアカウントJSONにclient_email/private_keyが含まれていません");
        }
    }

    public record AdSenseStatus(boolean configured, String accountId, String clientId, boolean hasClientSecret) {
    }

    @Transactional(readOnly = true)
    public AdSenseStatus getAdSenseStatus(Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        requireProjectExists(projectId);
        AnalyticsProjectSettingsClient.AdSenseStatus status = analyticsProjectSettingsClient.getAdSenseStatus(projectId);
        return new AdSenseStatus(status.configured(), status.accountId(), status.clientId(), status.hasClientSecret());
    }

    /** AdSenseパブリッシャーIDとGoogle OAuthクライアントID(秘匿情報ではない)をまとめて保存する。 */
    @Transactional
    public void setAdSenseSettings(Long projectId, String accountId, String clientId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        requireProjectExists(projectId);
        analyticsProjectSettingsClient.setAdSenseSettings(projectId, accountId, clientId);
    }

    @Transactional
    public void setAdSenseClientSecret(Long projectId, String clientSecret) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        requireProjectExists(projectId);
        analyticsProjectSettingsClient.setAdSenseClientSecret(projectId, clientSecret);
    }

    /**
     * Next.js側のOAuthコールバックルート(/connect/adsense/callback)から呼ばれる。認可コードを
     * リフレッシュトークンに交換して暗号化保存する処理自体はanalytics-service側(このプロジェクトに
     * 保存済みのアカウントID/クライアントID/シークレットを使う)で行う。
     */
    @Transactional
    public void completeAdSenseOAuth(Long projectId, String code, String redirectUri) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        requireProjectExists(projectId);
        analyticsProjectSettingsClient.completeAdSenseOAuth(projectId, code, redirectUri);
    }

    @Transactional
    public void clearAdSenseCredentials(Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        requireProjectExists(projectId);
        analyticsProjectSettingsClient.clearAdSenseCredentials(projectId);
    }

    /**
     * ArticlePlanService(GitHub Issue連携)から呼ばれる。プロジェクトにトークンが設定されていれば
     * それを優先し、未設定なら操作者本人のユーザー設定へフォールバックする(認可はここでは行わない。
     * 呼び出し元がプロジェクトメンバー/adminであることを別途保証している)。
     */
    @Transactional(readOnly = true)
    public String resolveGithubToken(Long projectId, Long actorUserId) {
        ProjectServiceClient.GithubTokenBridge token = projectServiceClient.getGithubToken(projectId);
        if (token.configured()) {
            return credentialCipher.decrypt(token.encryptedToken());
        }
        return userService.getDecryptedGithubToken(actorUserId);
    }

    private void requireProjectExists(Long projectId) {
        projectServiceClient.getProject(projectId);
    }
}
