package com.letsblog.api.controller;

import com.letsblog.api.dto.ProjectApiKeyStatusResponse;
import com.letsblog.api.dto.SetProjectBraveSearchApiKeyRequest;
import com.letsblog.api.dto.SetProjectGithubTokenRequest;
import com.letsblog.api.service.ProjectApiKeyService;
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
}
