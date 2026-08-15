package com.letsblog.api.adsense;

/** Google OAuth認可コード交換/トークン更新/AdSense Management API呼び出しに関する失敗を表す。 */
public class AdSenseException extends RuntimeException {
    public AdSenseException(String message, Throwable cause) {
        super(message, cause);
    }
}
