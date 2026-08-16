package com.letsblog.api.ai;

/**
 * LlmClientが呼び出しの都度参照するLLM接続設定。実装はAppSettingService(issue #403)が持ち、
 * DB設定(Web管理画面のシステム設定から変更可能)があればそれを優先し、なければ環境変数の値に
 * フォールバックする。BraveSearchClientと同じ方針で、LlmClient自身は設定がどこから来るかを知らない
 * (ai→serviceの依存を作らないため、インターフェースはこちら側で定義しservice側が実装する)。
 */
public interface LlmConfigProvider {
    String baseUrl();

    String apiKey();

    String defaultModel();

    long requestTimeoutSeconds();
}
