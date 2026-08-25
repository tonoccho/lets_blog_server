package com.letsblog.analytics.controller;

import com.letsblog.analytics.dto.AdSenseReportResponse;
import com.letsblog.analytics.dto.GoogleAnalyticsReportResponse;
import com.letsblog.analytics.service.AdSenseReportService;
import com.letsblog.analytics.service.GoogleAnalyticsReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * プロジェクトダッシュボード(issue #385)の各ウィジェットが取得するレポートデータ向けAPI。
 * Google Analytics(issue #386)・AdSense(issue #387)を実装。legacy-apiから移設(issue #578)。
 * パス自体は/api/projects/{projectId}/dashboard/**のまま(既存フロントのURLを変えないため、
 * gateway側でこのパスをanalytics-serviceへルーティングする)。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/dashboard")
public class ProjectDashboardController {

    private final GoogleAnalyticsReportService googleAnalyticsReportService;
    private final AdSenseReportService adSenseReportService;

    public ProjectDashboardController(
            GoogleAnalyticsReportService googleAnalyticsReportService,
            AdSenseReportService adSenseReportService) {
        this.googleAnalyticsReportService = googleAnalyticsReportService;
        this.adSenseReportService = adSenseReportService;
    }

    @GetMapping("/google-analytics")
    public GoogleAnalyticsReportResponse getGoogleAnalyticsReport(@PathVariable Long projectId) {
        return googleAnalyticsReportService.getReport(projectId);
    }

    @GetMapping("/adsense")
    public AdSenseReportResponse getAdSenseReport(@PathVariable Long projectId) {
        return adSenseReportService.getReport(projectId);
    }
}
