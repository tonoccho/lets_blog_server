package com.letsblog.analytics.analytics;

/** GA4 Data APIをdateディメンションで取得した日次データ1件分(issue #426)。dateは"yyyy-MM-dd"形式。 */
public record GoogleAnalyticsDailyDataPoint(String date, long sessions, long activeUsers, long pageViews) {
}
