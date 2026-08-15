package com.letsblog.api.dto;

import com.letsblog.api.adsense.AdSenseReport;

/**
 * プロジェクトダッシュボードのGoogle AdSenseウィジェット向けレスポンス(issue #387)。
 * eligible=falseはAdSense未設定または本番サイト未紐付けのため取得自体を行っていないことを表す
 * (GoogleAnalyticsReportResponseと同じ方針)。
 */
public record AdSenseReportResponse(
        boolean eligible,
        String estimatedEarnings,
        Long clicks,
        Long impressions,
        String periodLabel,
        String errorMessage
) {
    public static AdSenseReportResponse notEligible() {
        return new AdSenseReportResponse(false, null, null, null, null, null);
    }

    public static AdSenseReportResponse of(AdSenseReport report, String periodLabel) {
        return new AdSenseReportResponse(
                true, report.estimatedEarnings(), report.clicks(), report.impressions(), periodLabel, null);
    }

    public static AdSenseReportResponse error(String errorMessage) {
        return new AdSenseReportResponse(true, null, null, null, null, errorMessage);
    }
}
