package com.letsblog.api.dto;

import com.letsblog.api.analytics.GoogleAnalyticsReport;

/**
 * プロジェクトダッシュボードのGoogle Analyticsウィジェット向けレスポンス(issue #386)。
 * eligible=falseはGA未設定または本番サイト未紐付けのため取得自体を行っていないことを表す
 * (この場合errorMessageもnull。エラーではなく「まだ設定/対象外」であることをフロント側で区別するため)。
 */
public record GoogleAnalyticsReportResponse(
        boolean eligible,
        Long sessions,
        Long activeUsers,
        Long pageViews,
        String periodLabel,
        String errorMessage
) {
    public static GoogleAnalyticsReportResponse notEligible() {
        return new GoogleAnalyticsReportResponse(false, null, null, null, null, null);
    }

    public static GoogleAnalyticsReportResponse of(GoogleAnalyticsReport report, String periodLabel) {
        return new GoogleAnalyticsReportResponse(
                true, report.sessions(), report.activeUsers(), report.pageViews(), periodLabel, null);
    }

    public static GoogleAnalyticsReportResponse error(String errorMessage) {
        return new GoogleAnalyticsReportResponse(true, null, null, null, null, errorMessage);
    }
}
