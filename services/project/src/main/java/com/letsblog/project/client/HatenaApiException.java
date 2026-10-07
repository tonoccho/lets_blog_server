package com.letsblog.project.client;

/**
 * はてな(OAuth 1.0a のリクエストトークン・アクセストークン・自分の情報)との通信の失敗(issue #1582)。メッセージには consumer secret・
 * トークンとその秘密・verifier を含めない(HTTP ステータスと、はてなが返した oauth_problem だけ)。原因の例外は持たない。
 */
public class HatenaApiException extends RuntimeException {

    public HatenaApiException(String message) {
        super(message);
    }
}
