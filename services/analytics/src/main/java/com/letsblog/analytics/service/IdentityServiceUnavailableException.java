package com.letsblog.analytics.service;

/**
 * identity-service/legacy-apiへの同期呼び出し(#572のC12(#581)までの暫定策。
 * CurrentActorService/AdminAuthorizationServiceのJavadoc参照)がネットワークエラー・タイムアウト・
 * 想定外のレスポンスで失敗したことを表す。media-service(#573)/ai-service(#574)と同じ方針(issue #578)。
 */
public class IdentityServiceUnavailableException extends RuntimeException {
    public IdentityServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
