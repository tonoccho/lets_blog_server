package com.letsblog.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.analytics.GoogleServiceAccountKey;
import com.letsblog.api.crypto.CredentialCipher;
import com.letsblog.api.domain.Project;
import com.letsblog.api.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * プロジェクト単位のGitHubトークン/Brave Search APIキーを管理する(issue #184)。
 * プロジェクトに値が設定されていればそれを優先し、未設定の場合は
 * GitHubトークンは操作者本人のユーザー設定(UserService)、Brave Search APIキーは
 * システム全体設定(SystemSettingService)へフォールバックする(既存の動作を壊さないため)。
 */
@Service
public class ProjectApiKeyService {

    private final ProjectRepository projectRepository;
    private final CredentialCipher credentialCipher;
    private final UserService userService;
    private final SystemSettingService systemSettingService;
    private final AdminAuthorizationService adminAuthorizationService;
    private final ObjectMapper objectMapper;

    public ProjectApiKeyService(
            ProjectRepository projectRepository,
            CredentialCipher credentialCipher,
            UserService userService,
            SystemSettingService systemSettingService,
            AdminAuthorizationService adminAuthorizationService,
            ObjectMapper objectMapper) {
        this.projectRepository = projectRepository;
        this.credentialCipher = credentialCipher;
        this.userService = userService;
        this.systemSettingService = systemSettingService;
        this.adminAuthorizationService = adminAuthorizationService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public boolean isGithubTokenConfigured(Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return getProject(projectId).hasGithubToken();
    }

    @Transactional(readOnly = true)
    public boolean isBraveSearchApiKeyConfigured(Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return getProject(projectId).hasBraveSearchApiKey();
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
        Project project = getProject(projectId);
        project.setBraveSearchApiKeyEncrypted(credentialCipher.encrypt(apiKey));
        projectRepository.save(project);
    }

    @Transactional
    public void clearBraveSearchApiKey(Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        Project project = getProject(projectId);
        project.setBraveSearchApiKeyEncrypted(null);
        projectRepository.save(project);
    }

    @Transactional(readOnly = true)
    public boolean isGoogleAnalyticsConfigured(Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return getProject(projectId).hasGoogleAnalyticsCredentials();
    }

    @Transactional(readOnly = true)
    public String getGoogleAnalyticsPropertyId(Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return getProject(projectId).getGaPropertyId();
    }

    @Transactional
    public void setGoogleAnalyticsCredentials(Long projectId, String propertyId, String serviceAccountJson) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        validateGoogleServiceAccountJson(serviceAccountJson);
        Project project = getProject(projectId);
        project.setGaPropertyId(propertyId);
        project.setGaServiceAccountJsonEncrypted(credentialCipher.encrypt(serviceAccountJson));
        projectRepository.save(project);
    }

    @Transactional
    public void clearGoogleAnalyticsCredentials(Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        Project project = getProject(projectId);
        project.setGaPropertyId(null);
        project.setGaServiceAccountJsonEncrypted(null);
        projectRepository.save(project);
    }

    /**
     * GoogleAnalyticsReportServiceから呼ばれる。GA未設定の場合はnullを返す(認可はここでは行わない。
     * 呼び出し元がプロジェクトメンバー/adminであることを別途保証している。resolveGithubToken等と同じ方針)。
     */
    @Transactional(readOnly = true)
    public GoogleServiceAccountKey resolveGoogleAnalyticsServiceAccountKey(Long projectId) {
        Project project = getProject(projectId);
        if (!project.hasGoogleAnalyticsCredentials()) {
            return null;
        }
        String json = credentialCipher.decrypt(project.getGaServiceAccountJsonEncrypted());
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
        Project project = getProject(projectId);
        if (project.hasBraveSearchApiKey()) {
            return credentialCipher.decrypt(project.getBraveSearchApiKeyEncrypted());
        }
        return systemSettingService.getBraveSearchApiKey();
    }

    private Project getProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException("id " + projectId + " のプロジェクトは登録されていません"));
    }
}
