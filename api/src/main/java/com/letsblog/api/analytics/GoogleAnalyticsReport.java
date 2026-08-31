package com.letsblog.api.analytics;

import java.util.List;

/**
 * GA4 Data APIのrunReport結果から取り出した主要指標(issue #386)。
 * dailyDataPoints/channelBreakdownは時系列グラフ・内訳円グラフ表示用の追加データ(issue #426)。
 */
public record GoogleAnalyticsReport(
        long sessions,
        long activeUsers,
        long pageViews,
        List<GoogleAnalyticsDailyDataPoint> dailyDataPoints,
        List<GoogleAnalyticsChannelBreakdown> channelBreakdown) {
}
