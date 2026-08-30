package com.letsblog.logwriter.service;

/**
 * identity-service/legacy-apiへの同期呼び出し(#572のC12(#581)までの暫定策。
 * CurrentActorService/AdminAuthorizationService/UnifiedOperationLogServiceのJavadoc参照)が
 * ネットワークエラー・タイムアウト・想定外のレスポンスで失敗したことを表す。
 */
public class IdentityServiceUnavailableException extends RuntimeException {
    public IdentityServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
