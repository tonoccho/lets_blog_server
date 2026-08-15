package com.letsblog.api.analytics;

/** GA4 Data APIのrunReport結果から取り出した主要指標(issue #386)。 */
public record GoogleAnalyticsReport(long sessions, long activeUsers, long pageViews) {
}
