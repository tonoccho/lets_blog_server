package com.letsblog.identity.keycloak;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * identity-serviceがKeycloak Admin REST APIを呼び出すための設定(#562)。
 *
 * <p>client-idは"letsblog-services"(#560で定義済みのサービス間通信用confidentialクライアント)を
 * 既定値として再利用する。realm側でこのクライアントのサービスアカウントに
 * realm-management#manage-usersロールを付与しておく必要がある(infra/keycloak/realm-export.json参照)。
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
