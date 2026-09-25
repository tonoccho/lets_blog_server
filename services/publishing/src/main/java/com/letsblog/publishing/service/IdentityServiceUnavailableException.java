package com.letsblog.publishing.service;

/**
 * identity-service/legacy-apiへの同期呼び出し(C12(#581)までの暫定策。CurrentActorService/
 * AdminAuthorizationService/IdentityBridgeClientのJavadoc参照)がネットワークエラー・
 * タイムアウト・想定外のレスポンスで失敗したことを表す。media-service/ai-service/content-serviceと
 * 同じ方針。
 */
public class IdentityServiceUnavailableException extends RuntimeException {
    public IdentityServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
