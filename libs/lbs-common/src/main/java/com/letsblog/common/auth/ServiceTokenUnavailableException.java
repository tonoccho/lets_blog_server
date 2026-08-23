package com.letsblog.common.auth;

/**
 * サービス用アクセストークン(Client Credentials Grant)の取得に失敗したこと、
 * またはサーキットブレーカー作動中のため取得を試みなかったことを表す例外(#567)。
 *
 * <p>トークンエンドポイントへの到達不可・認証失敗・不正なレスポンスなど、原因を問わず
 * {@link ServiceTokenClient#getAccessToken()} はこの例外を送出する。呼び出し元はこれを
 * キャッチして、サービストークンが必要な処理全体を失敗させること
 * (トークンが取れないまま無認証で下流を呼び出す、といったフォールバックはしない)。
 */
public class ServiceTokenUnavailableException extends RuntimeException {

    public ServiceTokenUnavailableException(String message) {
        super(message);
    }

    public ServiceTokenUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
