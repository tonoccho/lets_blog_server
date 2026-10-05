package com.letsblog.project.client;

/**
 * Facebook(Graph API)との通信(コード交換・長期トークン化・ページ一覧の取得)の失敗(issue #1580)。メッセージにはアプリの秘密・
 * 認可コード・トークンを含めない(HTTP ステータスと Facebook が返した error.message だけ)。原因の例外は持たない
 * (トークン交換の URL のクエリにアプリの秘密が載るため、原因の例外のメッセージ経由でログに出さない)。
 */
public class FacebookApiException extends RuntimeException {

    public FacebookApiException(String message) {
        super(message);
    }
}
