package com.letsblog.api.dto;

import com.letsblog.api.adsense.AdSenseDailyDataPoint;

/** AdSenseウィジェットの日次推移グラフ用データ点(issue #426)。 */
public record AdSenseDailyDataPointResponse(String date, String estimatedEarnings, long clicks, long impressions) {
    public static AdSenseDailyDataPointResponse of(AdSenseDailyDataPoint point) {
        return new AdSenseDailyDataPointResponse(
                point.date(), point.estimatedEarnings(), point.clicks(), point.impressions());
    }
}
