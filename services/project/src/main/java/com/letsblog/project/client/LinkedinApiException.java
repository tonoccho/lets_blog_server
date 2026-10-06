package com.letsblog.project.client;

/**
 * LinkedIn API との通信(トークン交換・userinfo の取得)の失敗(issue #1581)。メッセージには Client Secret・
 * 認可コード・トークンを含めない(HTTP ステータスと LinkedIn が返した理由だけ)。原因の例外は持たない。
 */
public class LinkedinApiException extends RuntimeException {

    public LinkedinApiException(String message) {
        super(message);
    }
}
