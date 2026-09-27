package com.letsblog.analytics.analytics;

/**
 * Admin APIのaccountSummaries.listから取り出した、連携したGoogleアカウントがアクセスできるGA4プロパティ。
 * 設定画面の選択肢として表示名とプロパティID(数字のみ、"properties/"は含まない)を返す(issue #1231)。
 */
public record GoogleAnalyticsPropertySummary(String propertyId, String displayName, String accountDisplayName) {
}
