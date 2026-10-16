package com.letsblog.project.service;

/**
 * platform-serviceの内部ブリッジ(gitlab-config)が、接続の失敗・タイムアウト・2xx以外の応答・
 * 空の応答で読めなかったことを表す(issue #1858)。GlobalExceptionHandlerが理由つきの503にする。
 */
public class PlatformServiceUnavailableException extends RuntimeException {
    public PlatformServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
