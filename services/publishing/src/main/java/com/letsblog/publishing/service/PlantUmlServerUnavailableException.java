package com.letsblog.publishing.service;

/**
 * [plantuml]タグの展開で、PlantUMLサーバー(media-service経由)へ到達できなかったことを表す(issue #1759)。
 * 利用者の入力の誤りではなく依存先の不通なので、{@link InvalidPlantUmlTagException}(400)とは分け、
 * GlobalExceptionHandlerが502として返す。
 */
public class PlantUmlServerUnavailableException extends RuntimeException {
    public PlantUmlServerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
