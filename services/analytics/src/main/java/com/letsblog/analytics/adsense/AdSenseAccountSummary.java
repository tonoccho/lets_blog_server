package com.letsblog.analytics.adsense;

/**
 * AdSense Management API v2のaccounts.listから取り出した、連携したGoogleアカウントが利用できるAdSenseアカウント。
 * {@code accountId}はresource name({@code accounts/pub-XXXX})から{@code accounts/}を除いた素のパブリッシャーID
 * ({@code pub-XXXX})。reports:generateのURLは{@code /v2/accounts/{accountId}/...}と組み立てるため、
 * 接頭辞つきで保存すると{@code /}が{@code %2F}にエンコードされてURLが壊れる(issue #1232)。
 */
public record AdSenseAccountSummary(String accountId, String displayName) {
}
