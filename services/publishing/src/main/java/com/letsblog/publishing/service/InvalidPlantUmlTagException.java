package com.letsblog.publishing.service;

/**
 * [plantuml]組み込みタグの記法・レンダリングが不正な場合に投げる(issue #344、legacy-apiから移設。
 * issue #707)。投稿はこれを未捕捉のまま伝播させ、投稿自体を拒否する
 * ({@link com.letsblog.publishing.config.GlobalExceptionHandler}が400として返す)。
 */
public class InvalidPlantUmlTagException extends RuntimeException {
    public InvalidPlantUmlTagException(String message) {
        super(message);
    }

    public InvalidPlantUmlTagException(String message, Throwable cause) {
        super(message, cause);
    }
}
