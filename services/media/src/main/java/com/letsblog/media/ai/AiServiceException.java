package com.letsblog.media.ai;

/**
 * 外部AI/レンダリングサービス(PlantUML/Penpot等)呼び出しの失敗を表す。
 * legacy-apiの{@code com.letsblog.api.ai.AiServiceException}と同一の実装(#573でmedia-serviceへ移設)。
 */
public class AiServiceException extends RuntimeException {
    public AiServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
