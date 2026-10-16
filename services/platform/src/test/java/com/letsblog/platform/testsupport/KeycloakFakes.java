package com.letsblog.platform.testsupport;

import com.letsblog.platform.keycloak.KeycloakAdminClient;
import com.letsblog.platform.keycloak.KeycloakAdminProperties;
import org.springframework.web.client.RestClient;

/**
 * KeycloakのHTTP境界をサーバー側のフェイクで模した、実物の{@link KeycloakAdminClient}(issue #1825)。
 * トークンエンドポイントは{@code /token}、Admin APIは{@code /admin}の下に置く。
 */
public final class KeycloakFakes {

    private KeycloakFakes() {
    }

    /** トークンの発行(200)と、Admin APIの既定応答(キャッシュ無効化は204)を登録し、実物のクライアントを返す。 */
    public static KeycloakAdminClient client(FakeHttpServer http) {
        http.on("POST", "/token", FakeHttpServer.Response.ok("{\"access_token\":\"kc-token\",\"expires_in\":3600}"));
        http.on("POST", "/admin/clear-(user|realm|keys)-cache", FakeHttpServer.Response.empty(204));
        KeycloakAdminProperties properties = new KeycloakAdminProperties();
        properties.setTokenUri(http.baseUrl() + "/token");
        properties.setAdminBaseUri(http.baseUrl() + "/admin");
        properties.setClientId("letsblog-services");
        properties.setClientSecret("test-secret");
        return new KeycloakAdminClient(RestClient.builder(), properties);
    }
}
