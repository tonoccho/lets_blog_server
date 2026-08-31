package com.letsblog.project.controller;

import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.project.dto.ProjectApiKeyStatusResponse;
import com.letsblog.project.dto.SetProjectGithubTokenRequest;
import com.letsblog.project.service.AdminAuthorizationService;
import com.letsblog.project.service.ProjectService;
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
 * プロジェクト単位のGitHubトークン(issue #184)。値そのものは返さず、設定済みかどうかのみ返す。
 *
 * <p>issue #583でlegacy-apiの{@code ProjectApiKeyController}から移設した。移設前は
 * GitHubトークン(project-service所有)・Brave Search APIキー(ai-service所有)・
 * Google Analytics/AdSense(analytics-service所有)を1つのコントローラが横断集約しており、
 * そのために3本のブリッジクライアントが必要だった。#583で<b>所有サービスごとに分割</b>し、
 * gatewayが{@code /api/projects/*&#47;api-keys/}配下をパスごとに振り分ける形にした
 * (ADR-0004のデータ所有権と一致する)。
 *
 * <p>パスは{@code /api/projects/{projectId}/api-keys/github-token}のまま変えていない
 * (既存のWeb/VSCode拡張のURLを変えないため)。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/api-keys/github-token")
public class ProjectGithubTokenController {

    private final ProjectService projectService;
    private final CredentialCipher credentialCipher;
    private final AdminAuthorizationService adminAuthorizationService;

    public ProjectGithubTokenController(
            ProjectService projectService,
            CredentialCipher credentialCipher,
            AdminAuthorizationService adminAuthorizationService) {
        this.projectService = projectService;
        this.credentialCipher = credentialCipher;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @GetMapping
    public ProjectApiKeyStatusResponse status(@PathVariable Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        byte[] encrypted = projectService.getGithubTokenEncrypted(projectId);
        return new ProjectApiKeyStatusResponse(encrypted != null && encrypted.length > 0);
    }

    @PutMapping
    public ResponseEntity<Void> set(
            @PathVariable Long projectId, @Valid @RequestBody SetProjectGithubTokenRequest request) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        projectService.setGithubTokenEncrypted(projectId, credentialCipher.encrypt(request.githubToken()));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> clear(@PathVariable Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        projectService.setGithubTokenEncrypted(projectId, null);
        return ResponseEntity.noContent().build();
    }
}
