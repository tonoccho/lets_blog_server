package com.letsblog.api.adsense;

/** AdSense Management APIをDATEディメンションで取得した日次データ1件分(issue #426)。dateは"yyyy-MM-dd"形式。 */
public record AdSenseDailyDataPoint(String date, String estimatedEarnings, long clicks, long impressions) {
}
