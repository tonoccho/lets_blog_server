package com.letsblog.api.controller;

import com.letsblog.api.dto.AdSenseReportResponse;
import com.letsblog.api.dto.GoogleAnalyticsReportResponse;
import com.letsblog.api.dto.SocialStatsResponse;
import com.letsblog.api.service.AdSenseReportService;
import com.letsblog.api.service.GoogleAnalyticsReportService;
import com.letsblog.api.service.SocialStatsService;
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
    @Mock
    private AdSenseReportService adSenseReportService;
    @Mock
    private SocialStatsService socialStatsService;

    private ProjectDashboardController controller() {
        return new ProjectDashboardController(googleAnalyticsReportService, adSenseReportService, socialStatsService);
    }

    @Test
    void getGoogleAnalyticsReport_サービスの結果をそのまま返す() {
        GoogleAnalyticsReportResponse expected =
                GoogleAnalyticsReportResponse.of(
                        new com.letsblog.api.analytics.GoogleAnalyticsReport(1, 2, 3, java.util.List.of(), java.util.List.of()),
                        "過去28日間");
        when(googleAnalyticsReportService.getReport(1L)).thenReturn(expected);

        GoogleAnalyticsReportResponse response = controller().getGoogleAnalyticsReport(1L);

        assertTrue(response.eligible());
    }

    @Test
    void getAdSenseReport_サービスの結果をそのまま返す() {
        AdSenseReportResponse expected =
                AdSenseReportResponse.of(
                        new com.letsblog.api.adsense.AdSenseReport("12.34", 100, 5000, java.util.List.of(), java.util.List.of()),
                        "過去30日間");
        when(adSenseReportService.getReport(1L)).thenReturn(expected);

        AdSenseReportResponse response = controller().getAdSenseReport(1L);

        assertTrue(response.eligible());
    }

    @Test
    void getSocialStats_サービスの結果をそのまま返す() {
        SocialStatsResponse expected = SocialStatsResponse.of(3, 10, 5, 2, 20);
        when(socialStatsService.getStats(1L)).thenReturn(expected);

        SocialStatsResponse response = controller().getSocialStats(1L);

        assertTrue(response.eligible());
    }
}
