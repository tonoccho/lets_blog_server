package com.letsblog.project.controller;

import com.letsblog.project.dto.SnsTemplatesRequest;
import com.letsblog.project.dto.SnsTemplatesView;
import com.letsblog.project.service.AdminAuthorizationService;
import com.letsblog.project.service.SnsTemplateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** プロジェクト設定画面の「SNS 告知」欄の告知文テンプレートの API(issue #1583)。読むのはメンバーかadmin、保存と再送はadminだけ。 */
@RestController
@RequestMapping("/api/projects/{projectId}/sns/templates")
public class ProjectSnsTemplateController {

    private final SnsTemplateService snsTemplateService;
    private final AdminAuthorizationService adminAuthorizationService;

    public ProjectSnsTemplateController(
            SnsTemplateService snsTemplateService, AdminAuthorizationService adminAuthorizationService) {
        this.snsTemplateService = snsTemplateService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    /** 保存済みのテンプレート・本番サイトへの送信状態。 */
    @GetMapping
    public SnsTemplatesView view(@PathVariable Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return snsTemplateService.view(projectId);
    }

    /** 公開時と PV 達成時のテンプレートを保存し、本番サイトへ送る。 */
    @PutMapping
    public SnsTemplatesView save(@PathVariable Long projectId, @RequestBody SnsTemplatesRequest request) {
        adminAuthorizationService.requireAdmin();
        return snsTemplateService.save(projectId, request.publishTemplate(), request.pvTemplate());
    }

    /** 送信失敗からの回復。保存済みのテンプレートを本番サイトへ送り直す。 */
    @PostMapping("/resend")
    public SnsTemplatesView resend(@PathVariable Long projectId) {
        adminAuthorizationService.requireAdmin();
        return snsTemplateService.resend(projectId);
    }
}
