package com.letsblog.project.client;

/**
 * X API との通信(トークン交換・自分の情報の取得)の失敗(issue #1574)。メッセージにはクライアントの秘密・
 * 認可コード・トークンを含めない(HTTP ステータスと X が返した error / error_description だけ)。
 */
public class XApiException extends RuntimeException {

    public XApiException(String message) {
        super(message);
    }

    public XApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
