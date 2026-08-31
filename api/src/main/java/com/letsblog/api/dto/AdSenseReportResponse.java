package com.letsblog.api.dto;

import com.letsblog.api.adsense.AdSenseReport;

import java.util.List;

/**
 * プロジェクトダッシュボードのGoogle AdSenseウィジェット向けレスポンス(issue #387)。
 * eligible=falseはAdSense未設定または本番サイト未紐付けのため取得自体を行っていないことを表す
 * (GoogleAnalyticsReportResponseと同じ方針)。
 * dailyDataPoints/platformBreakdownは時系列グラフ・内訳円グラフ表示用(issue #426)。
 */
public record AdSenseReportResponse(
        boolean eligible,
        String estimatedEarnings,
        Long clicks,
        Long impressions,
        String periodLabel,
        String errorMessage,
        List<AdSenseDailyDataPointResponse> dailyDataPoints,
        List<AdSensePlatformBreakdownResponse> platformBreakdown
) {
    public static AdSenseReportResponse notEligible() {
        return new AdSenseReportResponse(false, null, null, null, null, null, List.of(), List.of());
    }

    public static AdSenseReportResponse of(AdSenseReport report, String periodLabel) {
        return new AdSenseReportResponse(
                true, report.estimatedEarnings(), report.clicks(), report.impressions(), periodLabel, null,
                report.dailyDataPoints().stream().map(AdSenseDailyDataPointResponse::of).toList(),
                report.platformBreakdown().stream().map(AdSensePlatformBreakdownResponse::of).toList());
    }

    public static AdSenseReportResponse error(String errorMessage) {
        return new AdSenseReportResponse(true, null, null, null, null, errorMessage, List.of(), List.of());
    }
}
