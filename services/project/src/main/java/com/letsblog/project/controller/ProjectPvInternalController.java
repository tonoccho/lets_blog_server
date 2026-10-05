package com.letsblog.project.controller;

import com.letsblog.project.service.AdminAuthorizationService;
import com.letsblog.project.service.PvRuleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * analytics-service が、GA のプロパティを選び終えた(連携が完了した)ことを知らせる内部API(issue #1578)。
 * 呼び出し元ユーザーのBearerトークンが転送され、プロジェクトのメンバーかadminであることを検査してから、
 * 本番サイトのプラグインへ GA4 の認証情報とルールを送る。送れなくても連携自体は成功のままで、
 * 結果は「送信失敗」として記録される(画面から再送できる)。
 */
@RestController
public class ProjectPvInternalController {

    private final PvRuleService pvRuleService;
    private final AdminAuthorizationService adminAuthorizationService;

    public ProjectPvInternalController(PvRuleService pvRuleService, AdminAuthorizationService adminAuthorizationService) {
        this.pvRuleService = pvRuleService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @PostMapping("/api/internal/project/projects/{projectId}/sns/pv/sync")
    public ResponseEntity<Void> googleAnalyticsConnected(@PathVariable Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        pvRuleService.onGoogleAnalyticsConnected(projectId);
        return ResponseEntity.noContent().build();
    }
}
