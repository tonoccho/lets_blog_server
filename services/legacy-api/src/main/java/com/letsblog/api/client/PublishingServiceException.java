package com.letsblog.api.client;

/**
 * publishing-serviceへの内部ブリッジ呼び出し(PublishingServiceClient)がネットワークエラー・
 * タイムアウト・想定外のレスポンス・CMS側の権限不足で失敗したことを表す(issue #707)。
 * legacy-apiのAnalyticsServiceException/AiServiceExceptionと同じ方針。
 */
public class PublishingServiceException extends RuntimeException {
    public PublishingServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
