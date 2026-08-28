package com.letsblog.platform.keycloak;

/**
 * Keycloak Admin APIとの呼び出しに失敗したことを表す例外(issue #693)。
 *
 * <p>legacy-api/identity-serviceの同名クラスと同じ考え方: Keycloakへの到達不可・認証失敗・
 * 対象ユーザー不在など原因を問わず、緊急復旧(AdminPasswordResetRunner)がKeycloak側の操作に
 * 失敗した場合は必ずこの例外を送出し、呼び出し元の操作全体を失敗させる。
 */
public class KeycloakAdminException extends RuntimeException {

    public KeycloakAdminException(String message) {
        super(message);
    }

    public KeycloakAdminException(String message, Throwable cause) {
        super(message, cause);
    }
}
