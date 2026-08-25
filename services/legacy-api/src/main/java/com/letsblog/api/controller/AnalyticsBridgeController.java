package com.letsblog.api.controller;

import com.letsblog.api.domain.Project;
import com.letsblog.api.repository.ProjectUserRepository;
import com.letsblog.api.service.ProjectService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * analytics-service向けの内部ブリッジ(issue #578)。GoogleAnalyticsReportService/AdSenseReportServiceが
 * レポート取得可否判定に使う「本番サイトが紐付いているか」、AdminAuthorizationServiceが使う
 * プロジェクトメンバー判定は、いずれもProject/project_user(project-serviceが未抽出のためlegacy-apiに
 * 残るドメイン)への依存が強いため、analytics-service側で直接持たず、このブリッジ経由でlegacy-apiへ
 * 問い合わせる(ai-service(#574)のAiBridgeControllerと同じ方針。認可は呼び出し元(analytics-service)が
 * 既にrequireProjectMemberOrAdmin等を済ませたリクエストのBearerトークンをそのまま転送してもらう想定で、
 * ここでは追加の認可チェックは行わない)。
 */
@RestController
public class AnalyticsBridgeController {

    private final ProjectService projectService;
    private final ProjectUserRepository projectUserRepository;

    public AnalyticsBridgeController(ProjectService projectService, ProjectUserRepository projectUserRepository) {
        this.projectService = projectService;
        this.projectUserRepository = projectUserRepository;
    }

    public record ProjectEligibilityResponse(boolean hasProductionSite) {
    }

    /**
     * GoogleAnalyticsReportService/AdSenseReportServiceが使う、レポート取得可否判定用のプロジェクト情報。
     * プロジェクトが存在しない場合はProjectNotFoundExceptionを投げ、GlobalExceptionHandlerが404として返す
     * (元のGoogleAnalyticsReportService/AdSenseReportService#getProjectと同じロジック)。
     */
    @GetMapping("/api/internal/analytics/projects/{projectId}")
    public ProjectEligibilityResponse projectEligibility(@PathVariable Long projectId) {
        Project project = projectService.getProjectEntity(projectId);
        return new ProjectEligibilityResponse(project.getProductionSiteId() != null);
    }

    /** AdminAuthorizationService(analytics-service)#requireProjectMemberOrAdminが使う、プロジェクトメンバー判定。 */
    @GetMapping("/api/internal/analytics/projects/{projectId}/members/{userId}")
    public boolean isProjectMember(@PathVariable Long projectId, @PathVariable Long userId) {
        return projectUserRepository.findByProjectIdAndUserId(projectId, userId).isPresent();
    }
}
