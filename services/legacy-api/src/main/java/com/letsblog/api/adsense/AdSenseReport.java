package com.letsblog.api.adsense;

import java.util.List;

/**
 * AdSense Management APIのreports:generate結果から取り出した主要指標(issue #387)。
 * estimatedEarningsは通貨(小数・通貨コードはアカウントごとに異なる)のためAPIが返す文字列表現をそのまま保持する。
 * dailyDataPoints/platformBreakdownは時系列グラフ・内訳円グラフ表示用の追加データ(issue #426)。
 */
public record AdSenseReport(
        String estimatedEarnings,
        long clicks,
        long impressions,
        List<AdSenseDailyDataPoint> dailyDataPoints,
        List<AdSensePlatformBreakdown> platformBreakdown) {
}
