package com.letsblog.api.analytics;

/** Google OAuth認証/GA4 Data API呼び出しに関する失敗を表す。 */
public class GoogleAnalyticsException extends RuntimeException {
    public GoogleAnalyticsException(String message, Throwable cause) {
        super(message, cause);
    }
}
