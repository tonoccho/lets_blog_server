package com.letsblog.api.adsense;

/**
 * AdSense Management APIのreports:generate結果から取り出した主要指標(issue #387)。
 * estimatedEarningsは通貨(小数・通貨コードはアカウントごとに異なる)のためAPIが返す文字列表現をそのまま保持する。
 */
public record AdSenseReport(String estimatedEarnings, long clicks, long impressions) {
}
