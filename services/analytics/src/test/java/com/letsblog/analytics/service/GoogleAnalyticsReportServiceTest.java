package com.letsblog.analytics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.analytics.analytics.GoogleAnalyticsChannelBreakdown;
import com.letsblog.analytics.analytics.GoogleAnalyticsClient;
import com.letsblog.analytics.analytics.GoogleAnalyticsDailyDataPoint;
import com.letsblog.analytics.analytics.GoogleAnalyticsException;
import com.letsblog.analytics.analytics.GoogleAnalyticsReport;
import com.letsblog.analytics.client.ProjectBridgeClient;
import com.letsblog.analytics.dto.GoogleAnalyticsReportResponse;
import com.letsblog.common.crypto.CredentialCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GoogleAnalyticsReportServiceの回帰テスト(issue #386、issue #578でanalytics-serviceへ移設)。
 * GA未設定/本番サイト未紐付けの場合にeligible=falseを返しGA4 Data APIへ問い合わせないこと、
 * レポート取得失敗時にeligible=trueのままerrorMessageを設定する(例外を投げない)ことを検証する。
 * legacy-api版と異なり、本番サイトの有無はProjectRepositoryではなくProjectBridgeClient経由の
 * ブリッジ呼び出しで判定する(project-service未抽出のためprojectsテーブルはlegacy-apiに残るため)。
 */
@ExtendWith(MockitoExtension.class)
class GoogleAnalyticsReportServiceTest {

    @Mock
    private ProjectBridgeClient projectBridgeClient;
    @Mock
    private AnalyticsCredentialsService analyticsCredentialsService;
    @Mock
    private GoogleAnalyticsClient googleAnalyticsClient;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;
    @Mock
    private CurrentActorService currentActorService;

    private final CredentialCipher credentialCipher = new CredentialCipher(
            Base64.getEncoder().encodeToString(new byte[32]));
    private final ObjectMapper objectMapper = new ObjectMapper();

    private GoogleAnalyticsReportService service() {
        return new GoogleAnalyticsReportService(
                projectBridgeClient, analyticsCredentialsService, googleAnalyticsClient,
                adminAuthorizationService, currentActorService, credentialCipher, objectMapper);
    }

    private static final String SERVICE_ACCOUNT_JSON =
            "{\"client_email\":\"svc@example.com\",\"private_key\":\"key\"}";

    @Test
    void getReport_GA未設定なら未対象でAPIを呼ばない() {
        lenient().when(projectBridgeClient.getProjectEligibility(eq(1L), any()))
                .thenReturn(new ProjectBridgeClient.ProjectEligibility(true));
        when(analyticsCredentialsService.hasGoogleAnalyticsCredentials(1L)).thenReturn(false);

        GoogleAnalyticsReportResponse response = service().getReport(1L);

        assertFalse(response.eligible());
        verify(googleAnalyticsClient, never()).fetchReport(any(), any(), anyInt());
    }

    @Test
    void getReport_本番サイト未紐付けなら未対象でAPIを呼ばない() {
        lenient().when(projectBridgeClient.getProjectEligibility(eq(1L), any()))
                .thenReturn(new ProjectBridgeClient.ProjectEligibility(false));
        when(analyticsCredentialsService.hasGoogleAnalyticsCredentials(1L)).thenReturn(true);

        GoogleAnalyticsReportResponse response = service().getReport(1L);

        assertFalse(response.eligible());
        verify(googleAnalyticsClient, never()).fetchReport(any(), any(), anyInt());
    }

    @Test
    void getReport_設定済みかつ本番サイトありならレポートを取得する() {
        lenient().when(projectBridgeClient.getProjectEligibility(eq(1L), any()))
                .thenReturn(new ProjectBridgeClient.ProjectEligibility(true));
        when(analyticsCredentialsService.hasGoogleAnalyticsCredentials(1L)).thenReturn(true);
        when(analyticsCredentialsService.getGaPropertyId(1L)).thenReturn("123456789");
        when(analyticsCredentialsService.getGaServiceAccountJsonEncrypted(1L))
                .thenReturn(credentialCipher.encrypt(SERVICE_ACCOUNT_JSON));
        when(googleAnalyticsClient.fetchReport(any(), eq("123456789"), anyInt()))
                .thenReturn(new GoogleAnalyticsReport(120, 80, 300,
                        List.of(new GoogleAnalyticsDailyDataPoint("2024-01-01", 10, 5, 20)),
                        List.of(new GoogleAnalyticsChannelBreakdown("Organic Search", 120, 80, 300))));

        GoogleAnalyticsReportResponse response = service().getReport(1L);

        assertTrue(response.eligible());
        assertEquals(120L, response.sessions());
        assertEquals(80L, response.activeUsers());
        assertEquals(300L, response.pageViews());
        assertNotNull(response.periodLabel());
        assertEquals(1, response.dailyDataPoints().size());
        assertEquals("2024-01-01", response.dailyDataPoints().get(0).date());
        assertEquals(1, response.channelBreakdown().size());
        assertEquals("Organic Search", response.channelBreakdown().get(0).channel());
    }

    @Test
    void getReport_取得に失敗したら対象のままerrorMessageを設定する() {
        lenient().when(projectBridgeClient.getProjectEligibility(eq(1L), any()))
                .thenReturn(new ProjectBridgeClient.ProjectEligibility(true));
        when(analyticsCredentialsService.hasGoogleAnalyticsCredentials(1L)).thenReturn(true);
        when(analyticsCredentialsService.getGaPropertyId(1L)).thenReturn("123456789");
        when(analyticsCredentialsService.getGaServiceAccountJsonEncrypted(1L))
                .thenReturn(credentialCipher.encrypt(SERVICE_ACCOUNT_JSON));
        when(googleAnalyticsClient.fetchReport(any(), eq("123456789"), anyInt()))
                .thenThrow(new GoogleAnalyticsException("APIエラー", null));

        GoogleAnalyticsReportResponse response = service().getReport(1L);

        assertTrue(response.eligible());
        assertEquals("APIエラー", response.errorMessage());
    }

    @Test
    void getReport_プロジェクトメンバーでなければForbidden() {
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(1L);

        assertThrows(ForbiddenException.class, () -> service().getReport(1L));
    }
}
