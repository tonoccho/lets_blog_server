package com.letsblog.api.controller;

import com.letsblog.api.dto.GoogleAnalyticsReportResponse;
import com.letsblog.api.service.GoogleAnalyticsReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * プロジェクトダッシュボード(issue #385)の各ウィジェットが取得するレポートデータ向けAPI。
 * まずGoogle Analytics(issue #386)のみ実装し、AdSense(#387)等は今後同じ構成で追加する想定。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/dashboard")
public class ProjectDashboardController {

    private final GoogleAnalyticsReportService googleAnalyticsReportService;

    public ProjectDashboardController(GoogleAnalyticsReportService googleAnalyticsReportService) {
        this.googleAnalyticsReportService = googleAnalyticsReportService;
    }

    @GetMapping("/google-analytics")
    public GoogleAnalyticsReportResponse getGoogleAnalyticsReport(@PathVariable Long projectId) {
        return googleAnalyticsReportService.getReport(projectId);
    }
}
