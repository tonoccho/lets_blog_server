package com.letsblog.analytics.dto;

import com.letsblog.analytics.analytics.GoogleAnalyticsDailyDataPoint;

/** Google Analyticsウィジェットの日次推移グラフ用データ点(issue #426)。 */
public record GoogleAnalyticsDailyDataPointResponse(String date, long sessions, long activeUsers, long pageViews) {
    public static GoogleAnalyticsDailyDataPointResponse of(GoogleAnalyticsDailyDataPoint point) {
        return new GoogleAnalyticsDailyDataPointResponse(
                point.date(), point.sessions(), point.activeUsers(), point.pageViews());
    }
}
