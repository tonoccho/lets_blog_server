package com.letsblog.api.controller;

import com.letsblog.api.service.ProjectUserSyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * project-service向けの内部ブリッジ(issue #577 stage2)。project_userテーブルの所有権はまだ
 * legacy-apiに残る({@link ProjectUserSyncService}参照)ため、project-serviceへ移設した
 * ProjectEnvironmentSyncServiceが環境同期(DB同期)後にWordPressユーザーロールを再整合させる際は、
 * このブリッジ経由でlegacy-apiへ依頼する。
 */
@RestController
public class ProjectUserBridgeController {

    private final ProjectUserSyncService projectUserSyncService;

    public ProjectUserBridgeController(ProjectUserSyncService projectUserSyncService) {
        this.projectUserSyncService = projectUserSyncService;
    }

    /** ProjectEnvironmentSyncService(project-service)#sync がDB同期後に呼ぶ、サイト向けロール再整合。 */
    @PostMapping("/api/internal/project/project-users/{projectId}/sites/{siteId}/reconcile-roles")
    public ResponseEntity<Void> reconcileRolesForSite(@PathVariable Long projectId, @PathVariable Long siteId) {
        projectUserSyncService.reconcileRolesForSite(projectId, siteId);
        return ResponseEntity.noContent().build();
    }
}
