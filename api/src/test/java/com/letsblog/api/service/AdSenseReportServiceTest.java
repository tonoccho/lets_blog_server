package com.letsblog.api.service;

import com.letsblog.api.adsense.AdSenseClient;
import com.letsblog.api.adsense.AdSenseException;
import com.letsblog.api.adsense.AdSenseReport;
import com.letsblog.api.domain.Project;
import com.letsblog.api.dto.AdSenseReportResponse;
import com.letsblog.api.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * AdSenseReportServiceの回帰テスト(issue #387)。AdSense未設定/本番サイト未紐付けの場合に
 * eligible=falseを返しAPIへ問い合わせないこと、取得失敗時にeligible=trueのままerrorMessageを設定する
 * (例外を投げない)ことを検証する。
 */
@ExtendWith(MockitoExtension.class)
class AdSenseReportServiceTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ProjectApiKeyService projectApiKeyService;
    @Mock
    private AdSenseClient adSenseClient;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private AdSenseReportService service() {
        return new AdSenseReportService(projectRepository, projectApiKeyService, adSenseClient, adminAuthorizationService);
    }

    private Project configuredProject() {
        Project project = new Project();
        project.setId(1L);
        project.setProductionSiteId(99L);
        project.setAdsenseAccountId("pub-1234567890123456");
        project.setAdsenseRefreshTokenEncrypted(new byte[]{1, 2, 3});
        project.setAdsenseOauthClientId("client-id");
        project.setAdsenseOauthClientSecretEncrypted(new byte[]{4, 5, 6});
        return project;
    }

    @Test
    void getReport_AdSense未設定なら未対象でAPIを呼ばない() {
        Project project = new Project();
        project.setId(1L);
        project.setProductionSiteId(99L);
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        AdSenseReportResponse response = service().getReport(1L);

        assertFalse(response.eligible());
    }

    @Test
    void getReport_本番サイト未紐付けなら未対象でAPIを呼ばない() {
        Project project = new Project();
        project.setId(1L);
        project.setAdsenseAccountId("pub-1234567890123456");
        project.setAdsenseRefreshTokenEncrypted(new byte[]{1, 2, 3});
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        AdSenseReportResponse response = service().getReport(1L);

        assertFalse(response.eligible());
    }

    @Test
    void getReport_設定済みかつ本番サイトありならレポートを取得する() {
        Project project = configuredProject();
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(projectApiKeyService.resolveAdSenseRefreshToken(1L)).thenReturn("refresh-token");
        when(projectApiKeyService.resolveAdSenseOauthClientSecret(1L)).thenReturn("client-secret");
        when(adSenseClient.refreshAccessToken("client-id", "client-secret", "refresh-token")).thenReturn("access-token");
        when(adSenseClient.fetchReport("access-token", "pub-1234567890123456", "LAST_30_DAYS"))
                .thenReturn(new AdSenseReport("12.34", 100, 5000));

        AdSenseReportResponse response = service().getReport(1L);

        assertTrue(response.eligible());
        assertEquals("12.34", response.estimatedEarnings());
        assertEquals(100L, response.clicks());
        assertEquals(5000L, response.impressions());
        assertNotNull(response.periodLabel());
    }

    @Test
    void getReport_取得に失敗したら対象のままerrorMessageを設定する() {
        Project project = configuredProject();
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(projectApiKeyService.resolveAdSenseRefreshToken(1L)).thenReturn("refresh-token");
        when(projectApiKeyService.resolveAdSenseOauthClientSecret(1L)).thenReturn("client-secret");
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
