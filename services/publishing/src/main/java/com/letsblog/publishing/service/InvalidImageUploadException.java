package com.letsblog.publishing.service;

/**
 * 受け取った画像を処理できない(画素数が上限を超える)場合に投げる(issue #1717)。直せるのは送り手なので、
 * {@link com.letsblog.publishing.config.GlobalExceptionHandler}が400として理由つきで返す。
 */
public class InvalidImageUploadException extends RuntimeException {
    public InvalidImageUploadException(String message) {
        super(message);
    }
}
