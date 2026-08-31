package com.letsblog.api.dto;

import com.letsblog.api.analytics.GoogleAnalyticsChannelBreakdown;

/** Google Analyticsウィジェットのトラフィックソース別内訳円グラフ用データ(issue #426)。 */
public record GoogleAnalyticsChannelBreakdownResponse(String channel, long sessions, long activeUsers, long pageViews) {
    public static GoogleAnalyticsChannelBreakdownResponse of(GoogleAnalyticsChannelBreakdown breakdown) {
        return new GoogleAnalyticsChannelBreakdownResponse(
                breakdown.channel(), breakdown.sessions(), breakdown.activeUsers(), breakdown.pageViews());
    }
}
