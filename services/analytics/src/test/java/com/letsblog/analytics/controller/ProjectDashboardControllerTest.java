package com.letsblog.analytics.controller;

import com.letsblog.analytics.analytics.GoogleAnalyticsReport;
import com.letsblog.analytics.adsense.AdSenseReport;
import com.letsblog.analytics.dto.AdSenseReportResponse;
import com.letsblog.analytics.dto.GoogleAnalyticsReportResponse;
import com.letsblog.analytics.service.AdSenseReportService;
import com.letsblog.analytics.service.GoogleAnalyticsReportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/** legacy-apiから移設(issue #578)。ProjectDashboardControllerが各サービスの結果をそのまま返すことを検証する。 */
@ExtendWith(MockitoExtension.class)
class ProjectDashboardControllerTest {

    @Mock
    private GoogleAnalyticsReportService googleAnalyticsReportService;
    @Mock
    private AdSenseReportService adSenseReportService;

    private ProjectDashboardController controller() {
        return new ProjectDashboardController(googleAnalyticsReportService, adSenseReportService);
    }

    @Test
    void getGoogleAnalyticsReport_サービスの結果をそのまま返す() {
        GoogleAnalyticsReportResponse expected =
                GoogleAnalyticsReportResponse.of(
                        new GoogleAnalyticsReport(1, 2, 3, List.of(), List.of()), "過去28日間");
        when(googleAnalyticsReportService.getReport(1L)).thenReturn(expected);

        GoogleAnalyticsReportResponse response = controller().getGoogleAnalyticsReport(1L);

        assertTrue(response.eligible());
    }

    @Test
    void getAdSenseReport_サービスの結果をそのまま返す() {
        AdSenseReportResponse expected =
                AdSenseReportResponse.of(
                        new AdSenseReport("12.34", 100, 5000, List.of(), List.of()), "過去30日間");
        when(adSenseReportService.getReport(1L)).thenReturn(expected);

        AdSenseReportResponse response = controller().getAdSenseReport(1L);

        assertTrue(response.eligible());
    }
}
