package com.letsblog.content.client;

/**
 * ai-service/media-serviceへの内部同期呼び出し(AiGenerationClient/MediaRenderClient)が
 * 失敗したことを表す。legacy-apiのcom.letsblog.api.ai.AiServiceExceptionと同じ実装。
 */
public class AiServiceException extends RuntimeException {
    public AiServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
