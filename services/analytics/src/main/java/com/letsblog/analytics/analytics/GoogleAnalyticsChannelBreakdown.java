package com.letsblog.analytics.analytics;

/** GA4 Data APIをsessionDefaultChannelGroupディメンションで取得したチャネル別内訳1件分(issue #426)。 */
public record GoogleAnalyticsChannelBreakdown(String channel, long sessions, long activeUsers, long pageViews) {
}
