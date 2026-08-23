package com.letsblog.identity.keycloak;

/**
 * Keycloak Admin APIとの同期に失敗したことを表す例外(#562の受入基準:
 * 「Keycloakが停止している場合にidentity-serviceが明確なエラーを返す(暗黙に成功しない)」)。
 *
 * <p>Keycloakへの到達不可・認証失敗・予期しないレスポンスなど、原因を問わず
 * ユーザー作成/更新/無効化/削除がKeycloak側と食い違う可能性がある状況では必ずこの例外を送出し、
 * 呼び出し元(UserService)の操作全体を失敗させる。ローカルDBだけを更新して見かけ上成功させることはしない。
 */
public class KeycloakUserSyncException extends RuntimeException {

    public KeycloakUserSyncException(String message) {
        super(message);
    }

    public KeycloakUserSyncException(String message, Throwable cause) {
        super(message, cause);
    }
}
