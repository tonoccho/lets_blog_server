package com.letsblog.project.client;

/**
 * ai-serviceへの内部同期呼び出し(AiGenerationClient)が失敗したことを表す。
 * legacy-apiのcom.letsblog.api.ai.AiServiceException/content-serviceの同名クラスと同じ実装。
 */
public class AiServiceException extends RuntimeException {
    public AiServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
