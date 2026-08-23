package com.letsblog.api.service;

import com.letsblog.api.adsense.AdSenseClient;
import com.letsblog.api.adsense.AdSenseReport;
import com.letsblog.api.domain.Project;
import com.letsblog.api.dto.AdSenseReportResponse;
import com.letsblog.api.repository.ProjectRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * プロジェクトダッシュボードのGoogle AdSenseウィジェット(issue #387)向けにレポートを取得する。
 * GoogleAnalyticsReportServiceと同様、AdSenseが未設定、または本番サイトが紐付いていない場合は
 * eligible=falseを返しAdSense Management APIへは問い合わせない。
 */
@Service
@Slf4j
public class AdSenseReportService {

    private static final String DATE_RANGE = "LAST_30_DAYS";
    private static final String PERIOD_LABEL = "過去30日間";

    private final ProjectRepository projectRepository;
    private final ProjectApiKeyService projectApiKeyService;
    private final AnalyticsCredentialsService analyticsCredentialsService;
    private final AdSenseClient adSenseClient;
    private final AdminAuthorizationService adminAuthorizationService;

    public AdSenseReportService(
            ProjectRepository projectRepository,
            ProjectApiKeyService projectApiKeyService,
            AnalyticsCredentialsService analyticsCredentialsService,
            AdSenseClient adSenseClient,
            AdminAuthorizationService adminAuthorizationService) {
        this.projectRepository = projectRepository;
        this.projectApiKeyService = projectApiKeyService;
        this.analyticsCredentialsService = analyticsCredentialsService;
        this.adSenseClient = adSenseClient;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @Transactional(readOnly = true)
    public AdSenseReportResponse getReport(Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        Project project = getProject(projectId);
        if (!analyticsCredentialsService.hasAdsenseCredentials(projectId) || project.getProductionSiteId() == null) {
            return AdSenseReportResponse.notEligible();
        }
        try {
            String refreshToken = projectApiKeyService.resolveAdSenseRefreshToken(projectId);
            String clientSecret = projectApiKeyService.resolveAdSenseOauthClientSecret(projectId);
            String clientId = analyticsCredentialsService.getAdsenseOauthClientId(projectId);
            String accessToken = adSenseClient.refreshAccessToken(clientId, clientSecret, refreshToken);
            String accountId = analyticsCredentialsService.getAdsenseAccountId(projectId);
            AdSenseReport report = adSenseClient.fetchReport(accessToken, accountId, DATE_RANGE);
            return AdSenseReportResponse.of(report, PERIOD_LABEL);
        } catch (RuntimeException e) {
            log.warn("AdSenseレポートの取得に失敗しました(project={}): {}", projectId, e.getMessage());
            return AdSenseReportResponse.error(e.getMessage());
        }
    }

    private Project getProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException("id " + projectId + " のプロジェクトは登録されていません"));
    }
}
