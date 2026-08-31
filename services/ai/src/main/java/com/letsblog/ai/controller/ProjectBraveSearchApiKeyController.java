package com.letsblog.ai.controller;

import com.letsblog.ai.dto.ProjectApiKeyStatusResponse;
import com.letsblog.ai.dto.SetProjectBraveSearchApiKeyRequest;
import com.letsblog.ai.service.AdminAuthorizationService;
import com.letsblog.ai.service.ProjectAiSettingsService;
import com.letsblog.common.crypto.CredentialCipher;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * プロジェクト単位のBrave Search APIキー(issue #184)。値そのものは返さず、
 * 設定済みかどうかのみ返す。
 *
 * <p>issue #583でlegacy-apiの{@code ProjectApiKeyController}から移設した。移設前は
 * 3サービスに散らばった資格情報を1つのコントローラが横断集約していたが、#583で
 * <b>所有サービスごとに分割</b>した({@code project_ai_settings}はai-service所有、#571/#574)。
 *
 * <p>パスは{@code /api/projects/{projectId}/api-keys/brave-search-api-key}のまま変えていない。
 *
 * <p>内部ブリッジ({@code InternalProjectAiSettingsController})は、legacy-api以外の
 * 呼び出し元(#583以降はplatform-service経由の実効設定解決)のために残す。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/api-keys/brave-search-api-key")
public class ProjectBraveSearchApiKeyController {

    private final ProjectAiSettingsService projectAiSettingsService;
    private final CredentialCipher credentialCipher;
    private final AdminAuthorizationService adminAuthorizationService;

    public ProjectBraveSearchApiKeyController(
            ProjectAiSettingsService projectAiSettingsService,
            CredentialCipher credentialCipher,
            AdminAuthorizationService adminAuthorizationService) {
        this.projectAiSettingsService = projectAiSettingsService;
        this.credentialCipher = credentialCipher;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @GetMapping
    public ProjectApiKeyStatusResponse status(@PathVariable Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return new ProjectApiKeyStatusResponse(projectAiSettingsService.hasBraveSearchApiKey(projectId));
    }

    @PutMapping
    public ResponseEntity<Void> set(
            @PathVariable Long projectId, @Valid @RequestBody SetProjectBraveSearchApiKeyRequest request) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        projectAiSettingsService.setBraveSearchApiKeyEncrypted(projectId, credentialCipher.encrypt(request.apiKey()));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> clear(@PathVariable Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        projectAiSettingsService.setBraveSearchApiKeyEncrypted(projectId, null);
        return ResponseEntity.noContent().build();
    }
}
