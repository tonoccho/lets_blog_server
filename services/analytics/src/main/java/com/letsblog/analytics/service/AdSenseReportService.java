package com.letsblog.analytics.service;

import com.letsblog.analytics.adsense.AdSenseClient;
import com.letsblog.analytics.adsense.AdSenseReport;
import com.letsblog.analytics.client.ProjectBridgeClient;
import com.letsblog.analytics.dto.AdSenseReportResponse;
import com.letsblog.common.crypto.CredentialCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * プロジェクトダッシュボードのGoogle AdSenseウィジェット(issue #387)向けにレポートを取得する
 * (issue #578でAdSenseReportService/ProjectApiKeyServiceのAdSense部分をanalytics-serviceへ移設)。
 * GoogleAnalyticsReportServiceと同様、AdSenseが未設定、または本番サイトが紐付いていない場合は
 * eligible=falseを返しAdSense Management APIへは問い合わせない。
 */
@Service
@Slf4j
public class AdSenseReportService {

    private static final String DATE_RANGE = "LAST_30_DAYS";
    private static final String PERIOD_LABEL = "過去30日間";

    private final ProjectBridgeClient projectBridgeClient;
    private final AnalyticsCredentialsService analyticsCredentialsService;
    private final AdSenseClient adSenseClient;
    private final AdminAuthorizationService adminAuthorizationService;
    private final CurrentActorService currentActorService;
    private final CredentialCipher credentialCipher;

    public AdSenseReportService(
            ProjectBridgeClient projectBridgeClient,
            AnalyticsCredentialsService analyticsCredentialsService,
            AdSenseClient adSenseClient,
            AdminAuthorizationService adminAuthorizationService,
            CurrentActorService currentActorService,
            CredentialCipher credentialCipher) {
        this.projectBridgeClient = projectBridgeClient;
        this.analyticsCredentialsService = analyticsCredentialsService;
        this.adSenseClient = adSenseClient;
        this.adminAuthorizationService = adminAuthorizationService;
        this.currentActorService = currentActorService;
        this.credentialCipher = credentialCipher;
    }

    @Transactional(readOnly = true)
    public AdSenseReportResponse getReport(Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        ProjectBridgeClient.ProjectEligibility eligibility =
                projectBridgeClient.getProjectEligibility(projectId, currentActorService.getAuthorizationHeader());
        if (!analyticsCredentialsService.hasAdsenseCredentials(projectId) || !eligibility.hasProductionSite()) {
            return AdSenseReportResponse.notEligible();
        }
        try {
            String refreshToken =
                    credentialCipher.decrypt(analyticsCredentialsService.getAdsenseRefreshTokenEncrypted(projectId));
            String clientSecret = analyticsCredentialsService.hasAdsenseOauthClientSecret(projectId)
                    ? credentialCipher.decrypt(analyticsCredentialsService.getAdsenseOauthClientSecretEncrypted(projectId))
                    : null;
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
}
