package com.letsblog.identity.controller;

import com.letsblog.identity.dto.AddProjectUserRequest;
import com.letsblog.identity.dto.ProjectUserResponse;
import com.letsblog.identity.dto.ProjectUserSummaryResponse;
import com.letsblog.identity.dto.ProjectUserSyncSiteResult;
import com.letsblog.identity.dto.UpdateProjectUserRequest;
import com.letsblog.identity.service.AdminAuthorizationService;
import com.letsblog.identity.service.ProjectUserSyncService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * プロジェクトメンバー({@code project_users})の管理。issue #583でlegacy-apiの
 * {@code ProjectController}(users部分)と{@code ProjectUserController}から移設した。
 *
 * <p>テーブルの所有権はidentity-service({@code lbs_identity}、ADR-0004)にある。
 * メンバーの追加・ロール変更はプロジェクトに紐付く各WordPress環境への即時同期を伴うため、
 * 実処理は{@link ProjectUserSyncService}が持つ。
 */
@RestController
public class ProjectUserController {

    private final ProjectUserSyncService projectUserSyncService;
    private final AdminAuthorizationService adminAuthorizationService;

    public ProjectUserController(
            ProjectUserSyncService projectUserSyncService,
            AdminAuthorizationService adminAuthorizationService) {
        this.projectUserSyncService = projectUserSyncService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @GetMapping("/api/projects/{id}/users")
    public List<ProjectUserResponse> listUsers(@PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        return projectUserSyncService.getProjectUsers(id);
    }

    @PostMapping("/api/projects/{id}/users")
    public ResponseEntity<Void> addUser(@PathVariable Long id, @Valid @RequestBody AddProjectUserRequest request) {
        adminAuthorizationService.requireAdmin();
        projectUserSyncService.addUserToProject(id, request.userId(), request.wpRole());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/projects/{id}/users/{userId}")
    public ResponseEntity<Void> updateUserRole(
            @PathVariable Long id, @PathVariable Long userId, @Valid @RequestBody UpdateProjectUserRequest request) {
        adminAuthorizationService.requireAdmin();
        projectUserSyncService.updateUserProjectRole(id, userId, request.wpRole());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/projects/{id}/users/{userId}")
    public ResponseEntity<Void> removeUser(@PathVariable Long id, @PathVariable Long userId) {
        adminAuthorizationService.requireAdmin();
        projectUserSyncService.removeUserFromProject(id, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * issue #1242: メンバー個別のユーザー情報再同期。ロール変更を伴わず、現在のプロフィールを
     * プロジェクトに紐づく全WordPress環境へ再送信する。一部の環境が失敗しても他の環境の結果は
     * 保持され、応答にサイトごとの成否と失敗理由が含まれる(要件3)。
     */
    @PostMapping("/api/projects/{id}/users/{userId}/sync")
    public List<ProjectUserSyncSiteResult> syncUser(@PathVariable Long id, @PathVariable Long userId) {
        adminAuthorizationService.requireAdmin();
        return projectUserSyncService.syncUserProfileToProjectSites(id, userId);
    }

    /** 全プロジェクトのメンバー割り当て一覧(Web管理画面のユーザー編集画面が使う)。 */
    @GetMapping("/api/project-users")
    public List<ProjectUserSummaryResponse> listAll() {
        adminAuthorizationService.requireAdmin();
        return projectUserSyncService.listAllProjectUsers();
    }
}
