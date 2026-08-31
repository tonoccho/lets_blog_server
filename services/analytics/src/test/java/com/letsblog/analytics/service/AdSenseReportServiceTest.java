package com.letsblog.analytics.service;

import com.letsblog.analytics.adsense.AdSenseClient;
import com.letsblog.analytics.adsense.AdSenseDailyDataPoint;
import com.letsblog.analytics.adsense.AdSenseException;
import com.letsblog.analytics.adsense.AdSensePlatformBreakdown;
import com.letsblog.analytics.adsense.AdSenseReport;
import com.letsblog.analytics.client.ProjectBridgeClient;
import com.letsblog.analytics.dto.AdSenseReportResponse;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * AdSenseReportServiceの回帰テスト(issue #387、issue #578でanalytics-serviceへ移設)。AdSense未設定/
 * 本番サイト未紐付けの場合にeligible=falseを返しAPIへ問い合わせないこと、取得失敗時にeligible=trueの
 * ままerrorMessageを設定する(例外を投げない)ことを検証する。legacy-api版と異なり、本番サイトの
 * 有無はProjectRepositoryではなくProjectBridgeClient経由のブリッジ呼び出しで判定し、リフレッシュ
 * トークン/クライアントシークレットの復号はProjectApiKeyServiceへの委譲ではなくこのサービス自身が
 * CredentialCipherで行う。
 */
@ExtendWith(MockitoExtension.class)
class AdSenseReportServiceTest {

    @Mock
    private ProjectBridgeClient projectBridgeClient;
    @Mock
    private AnalyticsCredentialsService analyticsCredentialsService;
    @Mock
    private AdSenseClient adSenseClient;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;
    @Mock
    private CurrentActorService currentActorService;

    private final CredentialCipher credentialCipher = new CredentialCipher(
            Base64.getEncoder().encodeToString(new byte[32]));

    private AdSenseReportService service() {
        return new AdSenseReportService(
                projectBridgeClient, analyticsCredentialsService, adSenseClient, adminAuthorizationService,
                currentActorService, credentialCipher);
    }

    private void stubConfiguredCredentials() {
        lenient().when(analyticsCredentialsService.hasAdsenseCredentials(1L)).thenReturn(true);
        lenient().when(analyticsCredentialsService.getAdsenseAccountId(1L)).thenReturn("pub-1234567890123456");
        lenient().when(analyticsCredentialsService.getAdsenseOauthClientId(1L)).thenReturn("client-id");
        lenient().when(analyticsCredentialsService.getAdsenseRefreshTokenEncrypted(1L))
                .thenReturn(credentialCipher.encrypt("refresh-token"));
        lenient().when(analyticsCredentialsService.hasAdsenseOauthClientSecret(1L)).thenReturn(true);
        lenient().when(analyticsCredentialsService.getAdsenseOauthClientSecretEncrypted(1L))
                .thenReturn(credentialCipher.encrypt("client-secret"));
    }

    @Test
    void getReport_AdSense未設定なら未対象でAPIを呼ばない() {
        lenient().when(projectBridgeClient.getProjectEligibility(eq(1L), any()))
                .thenReturn(new ProjectBridgeClient.ProjectEligibility(true));
        when(analyticsCredentialsService.hasAdsenseCredentials(1L)).thenReturn(false);

        AdSenseReportResponse response = service().getReport(1L);

        assertFalse(response.eligible());
    }

    @Test
    void getReport_本番サイト未紐付けなら未対象でAPIを呼ばない() {
        lenient().when(projectBridgeClient.getProjectEligibility(eq(1L), any()))
                .thenReturn(new ProjectBridgeClient.ProjectEligibility(false));
        when(analyticsCredentialsService.hasAdsenseCredentials(1L)).thenReturn(true);

        AdSenseReportResponse response = service().getReport(1L);

        assertFalse(response.eligible());
    }

    @Test
    void getReport_設定済みかつ本番サイトありならレポートを取得する() {
        lenient().when(projectBridgeClient.getProjectEligibility(eq(1L), any()))
                .thenReturn(new ProjectBridgeClient.ProjectEligibility(true));
        stubConfiguredCredentials();
        when(adSenseClient.refreshAccessToken("client-id", "client-secret", "refresh-token")).thenReturn("access-token");
        when(adSenseClient.fetchReport("access-token", "pub-1234567890123456", "LAST_30_DAYS"))
                .thenReturn(new AdSenseReport("12.34", 100, 5000,
                        List.of(new AdSenseDailyDataPoint("2024-01-01", "1.23", 10, 500)),
                        List.of(new AdSensePlatformBreakdown("Desktop", "12.34", 100, 5000))));

        AdSenseReportResponse response = service().getReport(1L);

        assertTrue(response.eligible());
        assertEquals("12.34", response.estimatedEarnings());
        assertEquals(100L, response.clicks());
        assertEquals(5000L, response.impressions());
        assertNotNull(response.periodLabel());
        assertEquals(1, response.dailyDataPoints().size());
        assertEquals("2024-01-01", response.dailyDataPoints().get(0).date());
        assertEquals(1, response.platformBreakdown().size());
        assertEquals("Desktop", response.platformBreakdown().get(0).platform());
    }

    @Test
    void getReport_取得に失敗したら対象のままerrorMessageを設定する() {
        lenient().when(projectBridgeClient.getProjectEligibility(eq(1L), any()))
                .thenReturn(new ProjectBridgeClient.ProjectEligibility(true));
        stubConfiguredCredentials();
        when(adSenseClient.refreshAccessToken(anyString(), anyString(), anyString()))
                .thenThrow(new AdSenseException("APIエラー", null));

        AdSenseReportResponse response = service().getReport(1L);

        assertTrue(response.eligible());
        assertEquals("APIエラー", response.errorMessage());
    }

    @Test
    void getReport_プロジェクトメンバーでなければForbidden() {
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(1L);

        assertThrows(ForbiddenException.class, () -> service().getReport(1L));
    }
}
