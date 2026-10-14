package com.letsblog.media.render;

/**
 * PlantUMLサーバーが応答したうえで図を拒否した(図の誤りなど)ことを表す(issue #1776)。
 * 接続できない場合({@link com.letsblog.media.ai.AiServiceException}、502)とは別物で、
 * 直せるのは利用者の入力なので400で返す。
 */
public class PlantUmlRenderRejectedException extends RuntimeException {
    public PlantUmlRenderRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
