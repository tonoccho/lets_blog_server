package com.letsblog.api.keycloak;

/**
 * Keycloak Admin APIとの呼び出しに失敗したことを表す例外(#681)。
 *
 * <p>services/identity/.../keycloak/KeycloakUserSyncExceptionと同じ考え方: Keycloakへの到達不可・
 * 認証失敗・対象ユーザー不在など原因を問わず、初回セットアップ(/api/auth/setup)・緊急復旧
 * (AdminPasswordResetRunner)がKeycloak側の操作に失敗した場合は必ずこの例外を送出し、
 * 呼び出し元の操作全体を失敗させる。ローカルDBだけを更新して見かけ上成功させることはしない。
 */
public class KeycloakAdminException extends RuntimeException {

    public KeycloakAdminException(String message) {
        super(message);
    }

    public KeycloakAdminException(String message, Throwable cause) {
        super(message, cause);
    }
}
