package com.letsblog.api.controller;

import com.letsblog.api.dto.GoogleAnalyticsReportResponse;
import com.letsblog.api.service.GoogleAnalyticsReportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectDashboardControllerTest {

    @Mock
    private GoogleAnalyticsReportService googleAnalyticsReportService;

    private ProjectDashboardController controller() {
        return new ProjectDashboardController(googleAnalyticsReportService);
    }

    @Test
    void getGoogleAnalyticsReport_サービスの結果をそのまま返す() {
        GoogleAnalyticsReportResponse expected =
                GoogleAnalyticsReportResponse.of(new com.letsblog.api.analytics.GoogleAnalyticsReport(1, 2, 3), "過去28日間");
        when(googleAnalyticsReportService.getReport(1L)).thenReturn(expected);

        GoogleAnalyticsReportResponse response = controller().getGoogleAnalyticsReport(1L);

        assertTrue(response.eligible());
    }
}
