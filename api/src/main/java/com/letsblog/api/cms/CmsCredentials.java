package com.letsblog.api.cms;

/**
 * CMS(現時点ではWordPressのみ)への接続情報。
 * 将来他CMSを追加する場合も、アダプタごとに必要な情報を保持する形で拡張する。
 */
public record CmsCredentials(String baseUrl, String username, String appPassword) {
}
