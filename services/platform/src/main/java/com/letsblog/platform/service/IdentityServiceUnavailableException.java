package com.letsblog.platform.service;

/**
 * identity-serviceへの同期呼び出し(CurrentActorService経由)が失敗したことを表す。
 * GlobalExceptionHandlerが502として扱う(log-writer(#572)/media-service(#573)と同じ方針)。
 */
public class IdentityServiceUnavailableException extends RuntimeException {

    public IdentityServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
