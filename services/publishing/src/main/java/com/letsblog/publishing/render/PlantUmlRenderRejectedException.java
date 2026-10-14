package com.letsblog.publishing.render;

/**
 * media-serviceが、PlantUMLサーバーによる図の拒否(400)を返したことを表す(issue #1776)。
 * 接続できない場合(502、PlantUmlServerUnavailableException)とは別物で、直せるのは利用者の入力なので
 * 400で返す。
 */
public class PlantUmlRenderRejectedException extends RuntimeException {
    public PlantUmlRenderRejectedException(String message) {
        super(message);
    }
}
