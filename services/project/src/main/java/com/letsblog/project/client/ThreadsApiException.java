package com.letsblog.project.client;

/**
 * Threads API との通信(トークン交換・長期トークン化・自分の情報の取得)の失敗(issue #1579)。メッセージにはアプリの秘密・
 * 認可コード・トークンを含めない(HTTP ステータスと Threads が返した error.message だけ)。原因の例外は持たない
 * (長期トークン化の URL のクエリにアプリの秘密が載るため、原因の例外のメッセージ経由でログに出さない)。
 */
public class ThreadsApiException extends RuntimeException {

    public ThreadsApiException(String message) {
        super(message);
    }
}
