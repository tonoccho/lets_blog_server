package com.letsblog.api.adsense;

/** AdSense Management APIをPLATFORM_TYPE_NAMEディメンションで取得したプラットフォーム別内訳1件分(issue #426)。 */
public record AdSensePlatformBreakdown(String platform, String estimatedEarnings, long clicks, long impressions) {
}
