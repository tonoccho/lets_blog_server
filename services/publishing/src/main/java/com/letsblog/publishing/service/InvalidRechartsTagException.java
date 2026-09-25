package com.letsblog.publishing.service;

/**
 * [recharts]組み込みタグの記法・属性・表データが不正な場合に投げる(issue #340、legacy-apiから移設。
 * issue #707)。投稿はこれを未捕捉のまま伝播させ、投稿自体を拒否する
 * ({@link com.letsblog.publishing.config.GlobalExceptionHandler}が400として返す)。
 */
public class InvalidRechartsTagException extends RuntimeException {
    public InvalidRechartsTagException(String message) {
        super(message);
    }
}
