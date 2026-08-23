package com.letsblog.api.service;

import com.letsblog.api.analytics.GoogleAnalyticsClient;
import com.letsblog.api.analytics.GoogleAnalyticsReport;
import com.letsblog.api.analytics.GoogleServiceAccountKey;
import com.letsblog.api.domain.Project;
import com.letsblog.api.dto.GoogleAnalyticsReportResponse;
import com.letsblog.api.repository.ProjectRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * プロジェクトダッシュボードのGoogle Analyticsウィジェット(issue #386)向けにGA4レポートを取得する。
 * GAが未設定、または本番サイトが紐付いていない場合はeligible=falseを返し、GA4 Data APIへは問い合わせない
 * (PostPublishService#isProductionSiteと同じ「productionSiteIdが設定されているか」で判定する。
 * NODE_ENV等サーバー全体の環境変数では判定しない)。
 */
@Service
@Slf4j
public class GoogleAnalyticsReportService {

    private static final int REPORT_PERIOD_DAYS = 28;
    private static final String PERIOD_LABEL = "過去28日間";

    private final ProjectRepository projectRepository;
    private final ProjectApiKeyService projectApiKeyService;
    private final AnalyticsCredentialsService analyticsCredentialsService;
    private final GoogleAnalyticsClient googleAnalyticsClient;
    private final AdminAuthorizationService adminAuthorizationService;

    public GoogleAnalyticsReportService(
            ProjectRepository projectRepository,
            ProjectApiKeyService projectApiKeyService,
            AnalyticsCredentialsService analyticsCredentialsService,
            GoogleAnalyticsClient googleAnalyticsClient,
            AdminAuthorizationService adminAuthorizationService) {
        this.projectRepository = projectRepository;
        this.projectApiKeyService = projectApiKeyService;
        this.analyticsCredentialsService = analyticsCredentialsService;
        this.googleAnalyticsClient = googleAnalyticsClient;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @Transactional(readOnly = true)
    public GoogleAnalyticsReportResponse getReport(Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        Project project = getProject(projectId);
        if (!analyticsCredentialsService.hasGoogleAnalyticsCredentials(projectId) || project.getProductionSiteId() == null) {
            return GoogleAnalyticsReportResponse.notEligible();
        }
        try {
            GoogleServiceAccountKey key = projectApiKeyService.resolveGoogleAnalyticsServiceAccountKey(projectId);
            String gaPropertyId = analyticsCredentialsService.getGaPropertyId(projectId);
            GoogleAnalyticsReport report =
                    googleAnalyticsClient.fetchReport(key, gaPropertyId, REPORT_PERIOD_DAYS);
            return GoogleAnalyticsReportResponse.of(report, PERIOD_LABEL);
        } catch (RuntimeException e) {
            log.warn("Google Analyticsレポートの取得に失敗しました(project={}): {}", projectId, e.getMessage());
            return GoogleAnalyticsReportResponse.error(e.getMessage());
        }
    }

    private Project getProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException("id " + projectId + " のプロジェクトは登録されていません"));
    }
}
