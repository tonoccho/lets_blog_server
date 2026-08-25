package com.letsblog.analytics.dto;

import com.letsblog.analytics.adsense.AdSensePlatformBreakdown;

/** AdSenseウィジェットの収益内訳(プラットフォーム別)円グラフ用データ(issue #426)。 */
public record AdSensePlatformBreakdownResponse(String platform, String estimatedEarnings, long clicks, long impressions) {
    public static AdSensePlatformBreakdownResponse of(AdSensePlatformBreakdown breakdown) {
        return new AdSensePlatformBreakdownResponse(
                breakdown.platform(), breakdown.estimatedEarnings(), breakdown.clicks(), breakdown.impressions());
    }
}
