package com.letsblog.api.service;

import com.letsblog.api.analytics.GoogleAnalyticsClient;
import com.letsblog.api.analytics.GoogleAnalyticsException;
import com.letsblog.api.analytics.GoogleAnalyticsReport;
import com.letsblog.api.analytics.GoogleServiceAccountKey;
import com.letsblog.api.domain.Project;
import com.letsblog.api.dto.GoogleAnalyticsReportResponse;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GoogleAnalyticsReportServiceの回帰テスト(issue #386)。GA未設定/本番サイト未紐付けの場合に
 * eligible=falseを返しGA4 Data APIへ問い合わせないこと、レポート取得失敗時にeligible=trueのまま
 * errorMessageを設定して返す(例外を投げない)ことを検証する。
 */
@ExtendWith(MockitoExtension.class)
class GoogleAnalyticsReportServiceTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ProjectApiKeyService projectApiKeyService;
    @Mock
    private GoogleAnalyticsClient googleAnalyticsClient;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private GoogleAnalyticsReportService service() {
        return new GoogleAnalyticsReportService(
                projectRepository, projectApiKeyService, googleAnalyticsClient, adminAuthorizationService);
    }

    private Project configuredProject() {
        Project project = new Project();
        project.setId(1L);
        project.setProductionSiteId(99L);
        project.setGaPropertyId("123456789");
        project.setGaServiceAccountJsonEncrypted(new byte[]{1, 2, 3});
        return project;
    }

    @Test
    void getReport_GA未設定なら未対象でAPIを呼ばない() {
        Project project = new Project();
        project.setId(1L);
        project.setProductionSiteId(99L);
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        GoogleAnalyticsReportResponse response = service().getReport(1L);

        assertFalse(response.eligible());
        verify(googleAnalyticsClient, never()).fetchReport(any(), any(), anyInt());
    }

    @Test
    void getReport_本番サイト未紐付けなら未対象でAPIを呼ばない() {
        Project project = new Project();
        project.setId(1L);
        project.setGaPropertyId("123456789");
        project.setGaServiceAccountJsonEncrypted(new byte[]{1, 2, 3});
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        GoogleAnalyticsReportResponse response = service().getReport(1L);

        assertFalse(response.eligible());
        verify(googleAnalyticsClient, never()).fetchReport(any(), any(), anyInt());
    }

    @Test
    void getReport_設定済みかつ本番サイトありならレポートを取得する() {
        Project project = configuredProject();
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        GoogleServiceAccountKey key = new GoogleServiceAccountKey("svc@example.com", "key", null);
        when(projectApiKeyService.resolveGoogleAnalyticsServiceAccountKey(1L)).thenReturn(key);
        when(googleAnalyticsClient.fetchReport(eq(key), eq("123456789"), anyInt()))
                .thenReturn(new GoogleAnalyticsReport(120, 80, 300));

        GoogleAnalyticsReportResponse response = service().getReport(1L);

        assertTrue(response.eligible());
        assertEquals(120L, response.sessions());
        assertEquals(80L, response.activeUsers());
        assertEquals(300L, response.pageViews());
        assertNotNull(response.periodLabel());
    }

    @Test
    void getReport_取得に失敗したら対象のままerrorMessageを設定する() {
        Project project = configuredProject();
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        GoogleServiceAccountKey key = new GoogleServiceAccountKey("svc@example.com", "key", null);
        when(projectApiKeyService.resolveGoogleAnalyticsServiceAccountKey(1L)).thenReturn(key);
        when(googleAnalyticsClient.fetchReport(eq(key), eq("123456789"), anyInt()))
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
