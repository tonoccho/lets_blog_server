package com.letsblog.project.controller;

import com.letsblog.project.dto.PvRuleRequest;
import com.letsblog.project.dto.PvRulesView;
import com.letsblog.project.service.AdminAuthorizationService;
import com.letsblog.project.service.PvRuleService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * プロジェクト設定画面の「SNS 告知」欄の PV 達成ルールの API(issue #1578)。GA4 の認証情報はどの応答にも載らない
 * (analytics-service から読んで本番サイトのプラグインへ送るだけ)。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/sns/pv")
public class ProjectSnsPvController {

    private final PvRuleService pvRuleService;
    private final AdminAuthorizationService adminAuthorizationService;

    public ProjectSnsPvController(PvRuleService pvRuleService, AdminAuthorizationService adminAuthorizationService) {
        this.pvRuleService = pvRuleService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    /** ルール・追加できるか(できない理由)・本番サイトへの送信状態。 */
    @GetMapping
    public PvRulesView view(@PathVariable Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return pvRuleService.view(projectId);
    }

    @PostMapping("/rules")
    public PvRulesView addRule(@PathVariable Long projectId, @Valid @RequestBody PvRuleRequest request) {
        adminAuthorizationService.requireAdmin();
        return pvRuleService.addRule(projectId, request.period(), request.threshold());
    }

    @DeleteMapping("/rules/{ruleId}")
    public PvRulesView deleteRule(@PathVariable Long projectId, @PathVariable String ruleId) {
        adminAuthorizationService.requireAdmin();
        return pvRuleService.deleteRule(projectId, ruleId);
    }

    /** 送信失敗からの回復。GA4 の認証情報とルール全件を送り直す。 */
    @PostMapping("/resend")
    public PvRulesView resend(@PathVariable Long projectId) {
        adminAuthorizationService.requireAdmin();
        return pvRuleService.resend(projectId);
    }
}
