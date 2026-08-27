package com.letsblog.api.keycloak;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * legacy-apiがKeycloak Admin REST APIを呼び出すための設定(#681)。
 *
 * <p>services/identity/.../keycloak/KeycloakAdminProperties と同じ設定キー・既定値を使う
 * (どちらも#560で定義済みの"letsblog-services"クライアントのクライアントクレデンシャルズグラントで
 * 認証する)。legacy-apiはidentity-serviceのGradleモジュールに依存できない(サービス間はHTTPのみで
 * 連携する方針。docker-compose.yml/settings.gradle参照)ため、同じ設定・実装をこのモジュール内に
 * 個別に持つ。realm側でこのクライアントのサービスアカウントにrealm-management#manage-usersロールが
 * 付与されている前提(keycloak/realm-export.json参照)。
 */
@ConfigurationProperties(prefix = "keycloak.admin")
@Getter
@Setter
public class KeycloakAdminProperties {

    /** クライアントクレデンシャルズグラントでアクセストークンを取得するトークンエンドポイント。 */
    private String tokenUri = "http://keycloak:8080/auth/realms/letsblog/protocol/openid-connect/token";

    /** Keycloak Admin REST APIのrealmベースURI(末尾に/usersなどを付けて利用する)。 */
    private String adminBaseUri = "http://keycloak:8080/auth/admin/realms/letsblog";

    private String clientId = "letsblog-services";

    private String clientSecret;
}
