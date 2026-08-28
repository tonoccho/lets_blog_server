package com.letsblog.platform.keycloak;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * platform-serviceがKeycloak Admin REST APIを呼び出すための設定(issue #693)。
 *
 * <p>services/legacy-api/.../keycloak/KeycloakAdminProperties・services/identity/.../keycloak/
 * KeycloakAdminPropertiesと同じ設定キー・既定値を使う(いずれも#560で定義済みの
 * "letsblog-services"クライアントのクライアントクレデンシャルズグラントで認証する)。
 * サービス間はGradleモジュールではなくHTTPのみで連携する方針(settings.gradle参照)のため、
 * 同じ設定・実装をこのモジュール内に個別に持つ。realm側でこのクライアントのサービスアカウントに
 * realm-management#manage-usersロールが付与されている前提(keycloak/realm-export.json参照)。
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
