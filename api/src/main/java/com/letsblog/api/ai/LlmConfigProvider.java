package com.letsblog.api.ai;

/**
 * LlmClientが呼び出しの都度参照するLLM接続設定。実装はAppSettingService(issue #403)が持ち、
 * DB設定(Web管理画面のシステム設定から変更可能)があればそれを優先し、なければ環境変数の値に
 * フォールバックする。BraveSearchClientと同じ方針で、LlmClient自身は設定がどこから来るかを知らない
 * (ai→serviceの依存を作らないため、インターフェースはこちら側で定義しservice側が実装する)。
 * baseUrl()/apiKey()/defaultModel()はprovider()が返す既定プロバイダの設定であり、
 * 個々の呼び出しでプロバイダを切り替えたい場合は*For(AiProvider)版を使う(issue #530)。
 */
public interface LlmConfigProvider {
    String baseUrl();

    String apiKey();

    String defaultModel();

    long requestTimeoutSeconds();

    /** システム設定で選択されている既定のAIプロバイダー。 */
    AiProvider provider();

    String apiKeyFor(AiProvider provider);

    String defaultModelFor(AiProvider provider);

    String baseUrlFor(AiProvider provider);
}
