package com.letsblog.publishing.render;

/**
 * media-serviceへのレンダリング呼び出し(MediaRenderClient)が失敗したことを表す。
 * legacy-apiのAiServiceException(PlantUmlEmbedService/PlantUmlTagRenderServiceが投げていたもの)を
 * publishing-service向けに切り出したもの(issue #707)。
 */
public class MediaRenderException extends RuntimeException {
    public MediaRenderException(String message, Throwable cause) {
        super(message, cause);
    }
}
