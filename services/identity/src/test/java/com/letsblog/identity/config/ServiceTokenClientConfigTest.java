package com.letsblog.identity.config;

import com.letsblog.common.auth.ServiceTokenClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** issue #1324: リクエスト外の内部ブリッジ呼び出しが使うClient Credentialsクライアントを公開する。 */
class ServiceTokenClientConfigTest {

    @Test
    void serviceTokenClient_keycloakの設定からトークンクライアントを作る() {
        ServiceTokenClient client = new ServiceTokenClientConfig().serviceTokenClient(
                "http://keycloak.invalid/token", "letsblog-services", "secret");

        assertNotNull(client);
    }
}
