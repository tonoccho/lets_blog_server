package com.letsblog.analytics.dto;

import com.letsblog.analytics.analytics.GoogleAnalyticsReport;

import java.util.List;

/**
 * プロジェクトダッシュボードのGoogle Analyticsウィジェット向けレスポンス(issue #386)。
 * eligible=falseはGA未設定または本番サイト未紐付けのため取得自体を行っていないことを表す
 * (この場合errorMessageもnull。エラーではなく「まだ設定/対象外」であることをフロント側で区別するため)。
 * dailyDataPoints/channelBreakdownは時系列グラフ・内訳円グラフ表示用(issue #426)。
 */
public record GoogleAnalyticsReportResponse(
        boolean eligible,
        Long sessions,
        Long activeUsers,
        Long pageViews,
        String periodLabel,
        String errorMessage,
        List<GoogleAnalyticsDailyDataPointResponse> dailyDataPoints,
        List<GoogleAnalyticsChannelBreakdownResponse> channelBreakdown
) {
    public static GoogleAnalyticsReportResponse notEligible() {
        return new GoogleAnalyticsReportResponse(false, null, null, null, null, null, List.of(), List.of());
    }

    public static GoogleAnalyticsReportResponse of(GoogleAnalyticsReport report, String periodLabel) {
        return new GoogleAnalyticsReportResponse(
                true, report.sessions(), report.activeUsers(), report.pageViews(), periodLabel, null,
                report.dailyDataPoints().stream().map(GoogleAnalyticsDailyDataPointResponse::of).toList(),
                report.channelBreakdown().stream().map(GoogleAnalyticsChannelBreakdownResponse::of).toList());
    }

    public static GoogleAnalyticsReportResponse error(String errorMessage) {
        return new GoogleAnalyticsReportResponse(true, null, null, null, null, errorMessage, List.of(), List.of());
    }
}
