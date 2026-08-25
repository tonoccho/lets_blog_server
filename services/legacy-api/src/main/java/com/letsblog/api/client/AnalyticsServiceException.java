package com.letsblog.api.client;

/**
 * analytics-serviceへの内部ブリッジ呼び出し(issue #578)がネットワークエラー・タイムアウト・
 * 想定外のレスポンスで失敗したことを表す(AiServiceExceptionと同じ方針)。
 */
public class AnalyticsServiceException extends RuntimeException {
    public AnalyticsServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
