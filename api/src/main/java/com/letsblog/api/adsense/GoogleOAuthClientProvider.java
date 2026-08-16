package com.letsblog.api.adsense;

/**
 * AdSenseClientが呼び出しの都度参照するGoogle OAuthクライアントID/シークレット。実装は
 * AppSettingService(issue #403)が持ち、DB設定(Web管理画面のシステム設定から変更可能)があれば
 * それを優先し、なければ環境変数の値にフォールバックする。LlmConfigProviderと同じ方針で、
 * AdSenseClient自身は設定がどこから来るかを知らない(adsense→serviceの依存を作らないため)。
 */
public interface GoogleOAuthClientProvider {
    String clientId();

    String clientSecret();
}
