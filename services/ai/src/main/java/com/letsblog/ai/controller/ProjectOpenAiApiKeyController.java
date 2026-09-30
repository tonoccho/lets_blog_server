package com.letsblog.ai.controller;

import com.letsblog.ai.dto.ProjectApiKeyStatusResponse;
import com.letsblog.ai.dto.SetProjectOpenAiApiKeyRequest;
import com.letsblog.ai.service.AdminAuthorizationService;
import com.letsblog.ai.service.ProjectAiSettingsService;
import com.letsblog.common.crypto.CredentialCipher;
import io.swagger.v3.oas.annotations.Operation;
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
 * プロジェクト単位のChatGPT(OpenAI) APIキー(issue #1506)。値そのものは返さず、設定済みかどうかのみ返す。
 * 保存したキーは{@code CredentialCipher}で暗号化し、そのプロジェクトのLLM生成(プロバイダーOPENAI)で
 * システム設定の{@code llm_api_key}より優先して使われる。認可はBrave Searchキーと同じ
 * {@code requireProjectMemberOrAdmin}。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/api-keys/openai-api-key")
public class ProjectOpenAiApiKeyController {

    private final ProjectAiSettingsService projectAiSettingsService;
    private final CredentialCipher credentialCipher;
    private final AdminAuthorizationService adminAuthorizationService;

    public ProjectOpenAiApiKeyController(
            ProjectAiSettingsService projectAiSettingsService,
            CredentialCipher credentialCipher,
            AdminAuthorizationService adminAuthorizationService) {
        this.projectAiSettingsService = projectAiSettingsService;
        this.credentialCipher = credentialCipher;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    // operationIdを明示するのは、既存のBrave Searchキーのoperation(status/set/clear)のidと生成クライアントの関数名を変えないため。
    @Operation(operationId = "getProjectOpenAiApiKeyStatus")
    @GetMapping
    public ProjectApiKeyStatusResponse status(@PathVariable Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return new ProjectApiKeyStatusResponse(projectAiSettingsService.hasOpenAiApiKey(projectId));
    }

    @Operation(operationId = "setProjectOpenAiApiKey")
    @PutMapping
    public ResponseEntity<Void> set(
            @PathVariable Long projectId, @Valid @RequestBody SetProjectOpenAiApiKeyRequest request) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        projectAiSettingsService.setOpenAiApiKeyEncrypted(projectId, credentialCipher.encrypt(request.apiKey()));
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "clearProjectOpenAiApiKey")
    @DeleteMapping
    public ResponseEntity<Void> clear(@PathVariable Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        projectAiSettingsService.setOpenAiApiKeyEncrypted(projectId, null);
        return ResponseEntity.noContent().build();
    }
}
