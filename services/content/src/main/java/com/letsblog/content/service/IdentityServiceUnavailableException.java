package com.letsblog.content.service;

/**
 * identity-service/legacy-apiへの同期呼び出し(C12(#581)までの暫定策。CurrentActorService/
 * AdminAuthorizationService/ProjectBridgeClientのJavadoc参照)がネットワークエラー・
 * タイムアウト・想定外のレスポンスで失敗したことを表す。media-service/ai-serviceと同じ方針。
 */
public class IdentityServiceUnavailableException extends RuntimeException {
    public IdentityServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
