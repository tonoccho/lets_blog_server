package com.letsblog.api.controller;

import com.letsblog.api.dto.AdSenseReportResponse;
import com.letsblog.api.dto.GoogleAnalyticsReportResponse;
import com.letsblog.api.service.AdSenseReportService;
import com.letsblog.api.service.GoogleAnalyticsReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * プロジェクトダッシュボード(issue #385)の各ウィジェットが取得するレポートデータ向けAPI。
 * Google Analytics(issue #386)・AdSense(issue #387)を実装。今後の連携(#390等)も同じ構成で追加する想定。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/dashboard")
public class ProjectDashboardController {

    private final GoogleAnalyticsReportService googleAnalyticsReportService;
    private final AdSenseReportService adSenseReportService;

    public ProjectDashboardController(
            GoogleAnalyticsReportService googleAnalyticsReportService, AdSenseReportService adSenseReportService) {
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
