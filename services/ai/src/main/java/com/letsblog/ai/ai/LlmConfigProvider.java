package com.letsblog.ai.ai;

import java.util.List;

/**
 * LlmClientが呼び出しの都度参照するLLM接続設定。
 *
 * <p>legacy-apiのAppSettingService(issue #403、Web管理画面のシステム設定)がDB設定(system_settings、
 * lbs_platformスキーマ相当。#574時点ではまだ移設先のplatform-serviceが存在しないためlegacy-apiに残る)
 * を正として持ち続けるため、ai-serviceはこの設定を直接読めない(ADR-0004: クロススキーマアクセス禁止)。
 * 実装は{@link com.letsblog.ai.client.LegacyApiLlmConfigClient}が、legacy-apiの内部ブリッジ
 * ({@code GET /api/internal/ai/llm-config})経由で都度解決する(issue #574)。
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

    /** プロジェクト画面のモデル選択ドロップダウンに表示する、選択可能なモデル名の一覧。 */
    List<String> availableModels();
}
