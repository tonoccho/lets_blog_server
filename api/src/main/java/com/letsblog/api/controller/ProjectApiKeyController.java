package com.letsblog.api.controller;

import com.letsblog.api.dto.CompleteAdSenseOAuthRequest;
import com.letsblog.api.dto.ProjectAdSenseStatusResponse;
import com.letsblog.api.dto.ProjectApiKeyStatusResponse;
import com.letsblog.api.dto.ProjectBufferStatusResponse;
import com.letsblog.api.dto.ProjectGoogleAnalyticsStatusResponse;
import com.letsblog.api.dto.SetProjectAdSenseClientSecretRequest;
import com.letsblog.api.dto.SetProjectAdSenseSettingsRequest;
import com.letsblog.api.dto.SetProjectBraveSearchApiKeyRequest;
import com.letsblog.api.dto.SetProjectBufferAccessTokenRequest;
import com.letsblog.api.dto.SetProjectBufferSettingsRequest;
import com.letsblog.api.dto.SetProjectGithubTokenRequest;
import com.letsblog.api.dto.SetProjectGoogleAnalyticsCredentialsRequest;
import com.letsblog.api.service.ProjectApiKeyService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * プロジェクト単位のGitHubトークン/Brave Search APIキーのWeb管理画面向けAPI(issue #184)。
 * 値そのものは返さず、設定済みかどうかのみを返す(SystemSettingControllerと同じ方針)。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/api-keys")
public class ProjectApiKeyController {

    private final ProjectApiKeyService projectApiKeyService;

    public ProjectApiKeyController(ProjectApiKeyService projectApiKeyService) {
        this.projectApiKeyService = projectApiKeyService;
    }

    @GetMapping("/github-token")
    public ProjectApiKeyStatusResponse getGithubTokenStatus(@PathVariable Long projectId) {
        return new ProjectApiKeyStatusResponse(projectApiKeyService.isGithubTokenConfigured(projectId));
    }

    @PutMapping("/github-token")
    public ResponseEntity<Void> setGithubToken(
            @PathVariable Long projectId, @Valid @RequestBody SetProjectGithubTokenRequest request) {
        projectApiKeyService.setGithubToken(projectId, request.githubToken());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/github-token")
    public ResponseEntity<Void> clearGithubToken(@PathVariable Long projectId) {
        projectApiKeyService.clearGithubToken(projectId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/brave-search-api-key")
    public ProjectApiKeyStatusResponse getBraveSearchApiKeyStatus(@PathVariable Long projectId) {
        return new ProjectApiKeyStatusResponse(projectApiKeyService.isBraveSearchApiKeyConfigured(projectId));
    }

    @PutMapping("/brave-search-api-key")
    public ResponseEntity<Void> setBraveSearchApiKey(
            @PathVariable Long projectId, @Valid @RequestBody SetProjectBraveSearchApiKeyRequest request) {
        projectApiKeyService.setBraveSearchApiKey(projectId, request.apiKey());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/brave-search-api-key")
    public ResponseEntity<Void> clearBraveSearchApiKey(@PathVariable Long projectId) {
        projectApiKeyService.clearBraveSearchApiKey(projectId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/google-analytics")
    public ProjectGoogleAnalyticsStatusResponse getGoogleAnalyticsStatus(@PathVariable Long projectId) {
        return new ProjectGoogleAnalyticsStatusResponse(
                projectApiKeyService.isGoogleAnalyticsConfigured(projectId),
                projectApiKeyService.getGoogleAnalyticsPropertyId(projectId));
    }

    @PutMapping("/google-analytics")
    public ResponseEntity<Void> setGoogleAnalyticsCredentials(
            @PathVariable Long projectId, @Valid @RequestBody SetProjectGoogleAnalyticsCredentialsRequest request) {
        projectApiKeyService.setGoogleAnalyticsCredentials(
                projectId, request.propertyId(), request.serviceAccountJson());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/google-analytics")
    public ResponseEntity<Void> clearGoogleAnalyticsCredentials(@PathVariable Long projectId) {
        projectApiKeyService.clearGoogleAnalyticsCredentials(projectId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/adsense")
    public ProjectAdSenseStatusResponse getAdSenseStatus(@PathVariable Long projectId) {
        ProjectApiKeyService.AdSenseStatus status = projectApiKeyService.getAdSenseStatus(projectId);
        return new ProjectAdSenseStatusResponse(
                status.configured(), status.accountId(), status.clientId(), status.hasClientSecret());
    }

    @PutMapping("/adsense")
    public ResponseEntity<Void> setAdSenseSettings(
            @PathVariable Long projectId, @Valid @RequestBody SetProjectAdSenseSettingsRequest request) {
        projectApiKeyService.setAdSenseSettings(projectId, request.accountId(), request.clientId());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/adsense/client-secret")
    public ResponseEntity<Void> setAdSenseClientSecret(
            @PathVariable Long projectId, @Valid @RequestBody SetProjectAdSenseClientSecretRequest request) {
        projectApiKeyService.setAdSenseClientSecret(projectId, request.clientSecret());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/adsense")
    public ResponseEntity<Void> clearAdSenseCredentials(@PathVariable Long projectId) {
        projectApiKeyService.clearAdSenseCredentials(projectId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/buffer")
    public ProjectBufferStatusResponse getBufferStatus(@PathVariable Long projectId) {
        ProjectApiKeyService.BufferSettingsStatus status = projectApiKeyService.getBufferSettingsStatus(projectId);
        return new ProjectBufferStatusResponse(
                status.configured(),
                status.enabled(),
                status.hasAccessToken(),
                status.profileIds(),
                status.delayMinutes(),
                status.messageTemplate());
    }

    @PutMapping("/buffer")
    public ResponseEntity<Void> setBufferSettings(
            @PathVariable Long projectId, @Valid @RequestBody SetProjectBufferSettingsRequest request) {
        projectApiKeyService.setBufferSettings(
                projectId, request.enabled(), request.profileIds(), request.delayMinutes(), request.messageTemplate());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/buffer/access-token")
    public ResponseEntity<Void> setBufferAccessToken(
            @PathVariable Long projectId, @Valid @RequestBody SetProjectBufferAccessTokenRequest request) {
        projectApiKeyService.setBufferAccessToken(projectId, request.accessToken());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/buffer")
    public ResponseEntity<Void> clearBufferSettings(@PathVariable Long projectId) {
        projectApiKeyService.clearBufferSettings(projectId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Next.js側のOAuthコールバックルート(/connect/adsense/callback)から呼ばれる、認可コード交換の完了通知。
     * ブラウザから直接叩かれるエンドポイントではない(nginxが/api/配下をSpring Bootへ直接転送するため、
     * ブラウザ発のOAuthリダイレクトはNext.js側のRoute Handlerで受け、そこからここをサーバー間で呼び出す)。
     */
    @PostMapping("/adsense/oauth-callback")
    public ResponseEntity<Void> completeAdSenseOAuth(
            @PathVariable Long projectId, @Valid @RequestBody CompleteAdSenseOAuthRequest request) {
        projectApiKeyService.completeAdSenseOAuth(projectId, request.code(), request.redirectUri());
        return ResponseEntity.noContent().build();
    }
}
