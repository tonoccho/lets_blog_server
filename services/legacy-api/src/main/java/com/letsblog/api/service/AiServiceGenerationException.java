package com.letsblog.api.service;

/** LLMによる静的コンテンツ生成、またはその前提となるプラグイン情報取得に失敗した場合の例外。 */
public class AiServiceGenerationException extends RuntimeException {
    public AiServiceGenerationException(String message) {
        super(message);
    }
}
