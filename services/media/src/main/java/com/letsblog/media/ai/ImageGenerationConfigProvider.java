package com.letsblog.media.ai;

/**
 * ComfyUiClient/ChatGptImageClientが呼び出しの都度参照する画像生成AIの接続設定(issue #531)。
 * 実装はAppSettingServiceが持ち、DB設定(Web管理画面のシステム設定から変更可能)があればそれを優先し、
 * なければ環境変数の値にフォールバックする。LlmConfigProviderと同じ方針で、aiパッケージが
 * serviceパッケージへ依存しないようにするため、インターフェースはこちら側で定義しservice側が実装する。
 */
public interface ImageGenerationConfigProvider {
    String comfyUiBaseUrl();

    String chatGptApiKey();

    String chatGptBaseUrl();
}
