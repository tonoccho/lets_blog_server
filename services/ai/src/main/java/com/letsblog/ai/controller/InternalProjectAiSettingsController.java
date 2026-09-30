package com.letsblog.ai.controller;

import com.letsblog.ai.service.ProjectAiSettingsService;
import com.letsblog.common.crypto.CredentialCipher;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * legacy-apiのProjectApiKeyController(Web管理画面向け、プロジェクト単位のBrave Search APIキー
 * 設定)がproject_ai_settings(ai-serviceが所有、issue #571/#574)を読み書きするための内部ブリッジ。
 *
 * <p>ProjectApiKeyControllerはGitHubトークン/Google Analytics/AdSense等、project-service/
 * analytics-serviceが未抽出のためlegacy-apiに残るプロジェクト単位の設定もまとめて扱う「god
 * controller」であり、そのうちBrave Search APIキーの部分だけがai-serviceの所有データになった
 * (media-service(#573)のCmsMediaBridgeControllerと同じ、追加の認可チェックは行わない方針。
 * legacy-api側のAdminAuthorizationService#requireProjectMemberOrAdminを既に済ませている)。
 */
@RestController
public class InternalProjectAiSettingsController {

    private final ProjectAiSettingsService projectAiSettingsService;
    private final CredentialCipher credentialCipher;

    public InternalProjectAiSettingsController(
            ProjectAiSettingsService projectAiSettingsService, CredentialCipher credentialCipher) {
        this.projectAiSettingsService = projectAiSettingsService;
        this.credentialCipher = credentialCipher;
    }

    public record BraveSearchApiKeyStatusResponse(boolean configured) {
    }

    @GetMapping("/api/internal/ai/projects/{projectId}/brave-search-api-key")
    public BraveSearchApiKeyStatusResponse status(@PathVariable Long projectId) {
        return new BraveSearchApiKeyStatusResponse(projectAiSettingsService.hasBraveSearchApiKey(projectId));
    }

    public record SetBraveSearchApiKeyRequest(String apiKey) {
    }

    @PutMapping("/api/internal/ai/projects/{projectId}/brave-search-api-key")
    public void set(@PathVariable Long projectId, @RequestBody SetBraveSearchApiKeyRequest request) {
        projectAiSettingsService.setBraveSearchApiKeyEncrypted(projectId, credentialCipher.encrypt(request.apiKey()));
    }

    @DeleteMapping("/api/internal/ai/projects/{projectId}/brave-search-api-key")
    public void clear(@PathVariable Long projectId) {
        projectAiSettingsService.setBraveSearchApiKeyEncrypted(projectId, null);
    }

    /** プロジェクトの接続先上書き。上書きが無い項目はnull(空文字は保存されない)。 */
    public record ProjectConnectionUrlsResponse(String ollamaBaseUrl, String comfyuiBaseUrl) {
    }

    /**
     * media-serviceがComfyUIの接続先を解決するための内部ブリッジ(issue #1503)。システム設定へのフォール
     * バックは呼び出し側が行う(値の所有者はplatform-service)。認可はサービス間認証のみ(他の内部ブリッジと同じ)。
     */
    @GetMapping("/api/internal/ai/projects/{projectId}/connections")
    public ProjectConnectionUrlsResponse connections(@PathVariable Long projectId) {
        return new ProjectConnectionUrlsResponse(
                projectAiSettingsService.getOllamaBaseUrl(projectId),
                projectAiSettingsService.getComfyuiBaseUrl(projectId));
    }
}
