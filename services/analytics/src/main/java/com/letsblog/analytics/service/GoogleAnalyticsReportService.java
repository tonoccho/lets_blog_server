package com.letsblog.analytics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.analytics.analytics.GoogleAnalyticsClient;
import com.letsblog.analytics.analytics.GoogleAnalyticsReport;
import com.letsblog.analytics.analytics.GoogleServiceAccountKey;
import com.letsblog.analytics.client.LegacyApiBridgeClient;
import com.letsblog.analytics.dto.GoogleAnalyticsReportResponse;
import com.letsblog.common.crypto.CredentialCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * プロジェクトダッシュボードのGoogle Analyticsウィジェット(issue #386)向けにGA4レポートを取得する
 * (issue #578でGoogleAnalyticsReportService/ProjectApiKeyServiceのGA部分をanalytics-serviceへ移設)。
 * GAが未設定、または本番サイトが紐付いていない場合はeligible=falseを返し、GA4 Data APIへは問い合わせない
 * (「productionSiteIdが設定されているか」の判定は{@link LegacyApiBridgeClient}経由でlegacy-apiへ
 * 問い合わせる。project-serviceが未抽出のためprojectsテーブル自体はlegacy-apiに残るため)。
 */
@Service
@Slf4j
public class GoogleAnalyticsReportService {

    private static final int REPORT_PERIOD_DAYS = 28;
    private static final String PERIOD_LABEL = "過去28日間";

    private final LegacyApiBridgeClient legacyApiBridgeClient;
    private final AnalyticsCredentialsService analyticsCredentialsService;
    private final GoogleAnalyticsClient googleAnalyticsClient;
    private final AdminAuthorizationService adminAuthorizationService;
    private final CurrentActorService currentActorService;
    private final CredentialCipher credentialCipher;
    private final ObjectMapper objectMapper;

    public GoogleAnalyticsReportService(
            LegacyApiBridgeClient legacyApiBridgeClient,
            AnalyticsCredentialsService analyticsCredentialsService,
            GoogleAnalyticsClient googleAnalyticsClient,
            AdminAuthorizationService adminAuthorizationService,
            CurrentActorService currentActorService,
            CredentialCipher credentialCipher,
            ObjectMapper objectMapper) {
        this.legacyApiBridgeClient = legacyApiBridgeClient;
        this.analyticsCredentialsService = analyticsCredentialsService;
        this.googleAnalyticsClient = googleAnalyticsClient;
        this.adminAuthorizationService = adminAuthorizationService;
        this.currentActorService = currentActorService;
        this.credentialCipher = credentialCipher;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public GoogleAnalyticsReportResponse getReport(Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        LegacyApiBridgeClient.ProjectEligibility eligibility =
                legacyApiBridgeClient.getProjectEligibility(projectId, currentActorService.getAuthorizationHeader());
        if (!analyticsCredentialsService.hasGoogleAnalyticsCredentials(projectId) || !eligibility.hasProductionSite()) {
            return GoogleAnalyticsReportResponse.notEligible();
        }
        try {
            GoogleServiceAccountKey key = resolveServiceAccountKey(projectId);
            String gaPropertyId = analyticsCredentialsService.getGaPropertyId(projectId);
            GoogleAnalyticsReport report =
                    googleAnalyticsClient.fetchReport(key, gaPropertyId, REPORT_PERIOD_DAYS);
            return GoogleAnalyticsReportResponse.of(report, PERIOD_LABEL);
        } catch (RuntimeException e) {
            log.warn("Google Analyticsレポートの取得に失敗しました(project={}): {}", projectId, e.getMessage());
            return GoogleAnalyticsReportResponse.error(e.getMessage());
        }
    }

    private GoogleServiceAccountKey resolveServiceAccountKey(Long projectId) {
        String json = credentialCipher.decrypt(analyticsCredentialsService.getGaServiceAccountJsonEncrypted(projectId));
        try {
            return objectMapper.readValue(json, GoogleServiceAccountKey.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("保存済みのサービスアカウントJSONの解析に失敗しました", e);
        }
    }
}
