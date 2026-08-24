package com.letsblog.ai.ai;

/**
 * 文章提案に使う外部LLMサービス(issue #530)。OLLAMA/OPENAIはOpenAI互換のChat Completions APIを
 * 共有するため接続設定(baseUrl/APIキー)の意味だけが異なり、CLAUDEはAnthropic独自のMessages APIを使う。
 */
public enum AiProvider {
    OLLAMA, OPENAI, CLAUDE;

    /** 未設定(null/空文字)はnullを返し、呼び出し側で「既定値を使う」の判定に使えるようにする。 */
    public static AiProvider fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return AiProvider.valueOf(value.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不明なAIプロバイダーです: " + value, e);
        }
    }
}
