package com.letsblog.media.service;

/**
 * アップロードされた画像を受け付けられない(形式・サイズ・中身が不正、issue #1599)。
 * 直せるのは送り手なので400で返す。
 */
public class InvalidImageUploadException extends RuntimeException {

    public InvalidImageUploadException(String message) {
        super(message);
    }
}
