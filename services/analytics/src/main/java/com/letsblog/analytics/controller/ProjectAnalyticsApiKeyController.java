package com.letsblog.analytics.controller;

import com.letsblog.analytics.service.AdminAuthorizationService;
import com.letsblog.analytics.service.ProjectAnalyticsSettingsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * プロジェクト単位の Google Analytics / AdSense 資格情報のWeb管理画面向けAPI(issue #184)。
 * 値そのものは返さず、設定済みかどうか(とアカウントID等の識別子)のみを返す。
 *
 * <p>issue #583でlegacy-apiの{@code ProjectApiKeyController}から移設した。移設前は
 * GitHubトークン(project-service所有)・Brave Search APIキー(ai-service所有)・
 * Google Analytics/AdSense(analytics-service所有)を1つのコントローラが横断集約し、
 * 3本のブリッジクライアント越しに各サービスへ中継していた。#583で<b>所有サービスごとに分割</b>し、
 * gatewayが{@code /api/projects/*&#47;api-keys/}配下をパスごとに振り分ける形にした。
 *
 * <p>パスは移設前と同じ({@code /api/projects/{projectId}/api-keys/google-analytics} 等)。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/api-keys")
public class ProjectAnalyticsApiKeyController {

    private final ProjectAnalyticsSettingsService projectAnalyticsSettingsService;
    private final AdminAuthorizationService adminAuthorizationService;

    public ProjectAnalyticsApiKeyController(
            ProjectAnalyticsSettingsService projectAnalyticsSettingsService,
            AdminAuthorizationService adminAuthorizationService) {
        this.projectAnalyticsSettingsService = projectAnalyticsSettingsService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    /**
     * GA連携の状態。{@code connected}はGoogleアカウントとの連携(リフレッシュトークン保存)済みか、
     * {@code configured}はさらにプロパティ選択まで済んでダッシュボードに表示できるか。
     * クライアントシークレット/リフレッシュトークンは値を返さず、シークレットは有無({@code hasClientSecret})のみ。
     */
    public record ProjectGoogleAnalyticsStatusResponse(
            boolean configured, String propertyId, String clientId, boolean hasClientSecret, boolean connected) {
    }

    /** {@code clientSecret}は省略(または空)なら既存の値を変更しない。 */
    public record SetProjectGoogleAnalyticsClientRequest(@NotBlank String clientId, String clientSecret) {
    }

    public record CompleteGoogleAnalyticsOAuthRequest(@NotBlank String code, @NotBlank String redirectUri) {
    }

    public record SelectGoogleAnalyticsPropertyRequest(@NotBlank String propertyId) {
    }

    public record GoogleAnalyticsPropertyResponse(String propertyId, String displayName, String accountDisplayName) {
    }

    public record ProjectAdSenseStatusResponse(
            boolean configured, String accountId, String clientId, boolean hasClientSecret) {
    }

    public record SetProjectAdSenseSettingsRequest(@NotBlank String accountId, String clientId) {
    }

    public record SetProjectAdSenseClientSecretRequest(@NotBlank String clientSecret) {
    }

    public record CompleteAdSenseOAuthRequest(@NotBlank String code, @NotBlank String redirectUri) {
    }

    @GetMapping("/google-analytics")
    public ProjectGoogleAnalyticsStatusResponse getGoogleAnalyticsStatus(@PathVariable Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return new ProjectGoogleAnalyticsStatusResponse(
                projectAnalyticsSettingsService.hasGoogleAnalytics(projectId),
                projectAnalyticsSettingsService.googleAnalyticsPropertyId(projectId),
                projectAnalyticsSettingsService.googleAnalyticsClientId(projectId),
                projectAnalyticsSettingsService.hasGoogleAnalyticsClientSecret(projectId),
                projectAnalyticsSettingsService.isGoogleAnalyticsConnected(projectId));
    }

    @PutMapping("/google-analytics/client")
    public ResponseEntity<Void> setGoogleAnalyticsClient(
            @PathVariable Long projectId, @Valid @RequestBody SetProjectGoogleAnalyticsClientRequest request) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        projectAnalyticsSettingsService.setGoogleAnalyticsClient(projectId, request.clientId(), request.clientSecret());
        return ResponseEntity.noContent().build();
    }

    /**
     * Next.js側のOAuthコールバックルート({@code /connect/google-analytics/callback})から呼ばれる、
     * 認可コード交換の完了通知(AdSenseの{@link #completeAdSenseOAuth}と同じ経路)。
     */
    @PostMapping("/google-analytics/oauth-callback")
    public ResponseEntity<Void> completeGoogleAnalyticsOAuth(
            @PathVariable Long projectId, @Valid @RequestBody CompleteGoogleAnalyticsOAuthRequest request) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        projectAnalyticsSettingsService.completeGoogleAnalyticsOAuth(projectId, request.code(), request.redirectUri());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/google-analytics/properties")
    public List<GoogleAnalyticsPropertyResponse> listGoogleAnalyticsProperties(@PathVariable Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return projectAnalyticsSettingsService.listGoogleAnalyticsProperties(projectId).stream()
                .map(p -> new GoogleAnalyticsPropertyResponse(p.propertyId(), p.displayName(), p.accountDisplayName()))
                .toList();
    }

    @PutMapping("/google-analytics/property")
    public ResponseEntity<Void> selectGoogleAnalyticsProperty(
            @PathVariable Long projectId, @Valid @RequestBody SelectGoogleAnalyticsPropertyRequest request) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        projectAnalyticsSettingsService.selectGoogleAnalyticsProperty(projectId, request.propertyId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/google-analytics")
    public ResponseEntity<Void> clearGoogleAnalyticsCredentials(@PathVariable Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        projectAnalyticsSettingsService.clearGoogleAnalyticsCredentials(projectId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/adsense")
    public ProjectAdSenseStatusResponse getAdSenseStatus(@PathVariable Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return new ProjectAdSenseStatusResponse(
                projectAnalyticsSettingsService.hasAdSense(projectId),
                projectAnalyticsSettingsService.adSenseAccountId(projectId),
                projectAnalyticsSettingsService.adSenseClientId(projectId),
                projectAnalyticsSettingsService.hasAdSenseClientSecret(projectId));
    }

    @PutMapping("/adsense")
    public ResponseEntity<Void> setAdSenseSettings(
            @PathVariable Long projectId, @Valid @RequestBody SetProjectAdSenseSettingsRequest request) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        projectAnalyticsSettingsService.setAdSenseSettings(projectId, request.accountId(), request.clientId());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/adsense/client-secret")
    public ResponseEntity<Void> setAdSenseClientSecret(
            @PathVariable Long projectId, @Valid @RequestBody SetProjectAdSenseClientSecretRequest request) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        projectAnalyticsSettingsService.setAdSenseClientSecret(projectId, request.clientSecret());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/adsense")
    public ResponseEntity<Void> clearAdSenseCredentials(@PathVariable Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        projectAnalyticsSettingsService.clearAdSenseCredentials(projectId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Next.js側のOAuthコールバックルート({@code /connect/adsense/callback})から呼ばれる、
     * 認可コード交換の完了通知。ブラウザから直接叩かれるエンドポイントではない
     * (ブラウザ発のOAuthリダイレクトはNext.js側のRoute Handlerで受け、そこからサーバー間で呼ぶ)。
     */
    @PostMapping("/adsense/oauth-callback")
    public ResponseEntity<Void> completeAdSenseOAuth(
            @PathVariable Long projectId, @Valid @RequestBody CompleteAdSenseOAuthRequest request) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        projectAnalyticsSettingsService.completeAdSenseOAuth(projectId, request.code(), request.redirectUri());
        return ResponseEntity.noContent().build();
    }
}
