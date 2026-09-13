package com.letsblog.identity.service;

/** issue #1241: アバター画像のファイルI/O(保存/読み込み)に失敗した場合に送出する。 */
public class AvatarStorageException extends RuntimeException {
    public AvatarStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
