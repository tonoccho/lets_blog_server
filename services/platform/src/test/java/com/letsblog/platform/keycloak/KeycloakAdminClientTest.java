package com.letsblog.platform.keycloak;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * KeycloakAdminClient#clearCachesの回帰テスト(issue #1590)。バックアップ復元でKeycloakのDBを
 * 書き換えた後、Infinispanキャッシュが古いユーザー情報を返し続けないことを保証する。
 */
class KeycloakAdminClientTest {

    private static final String TOKEN_URI = "http://keycloak-test/realms/letsblog/protocol/openid-connect/token";
    private static final String ADMIN_BASE_URI = "http://keycloak-test/admin/realms/letsblog";

    private KeycloakAdminClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        KeycloakAdminProperties properties = new KeycloakAdminProperties();
        properties.setTokenUri(TOKEN_URI);
        properties.setAdminBaseUri(ADMIN_BASE_URI);
        properties.setClientId("letsblog-services");
        properties.setClientSecret("test-secret");
        client = new KeycloakAdminClient(builder, properties);
    }

    private void expectToken() {
        server.expect(requestTo(TOKEN_URI))
                .andExpect(method(POST))
                .andExpect(content().string(containsString("grant_type=client_credentials")))
                .andRespond(withSuccess("{\"access_token\":\"tok\",\"expires_in\":60}", MediaType.APPLICATION_JSON));
    }

    private void expectClear(String path) {
        server.expect(requestTo(ADMIN_BASE_URI + path))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
    }

    @Test
    void clearCaches_ユーザー_レルム_鍵のキャッシュを無効化する() {
        expectToken();
        expectClear("/clear-user-cache");
        expectClear("/clear-realm-cache");
        expectClear("/clear-keys-cache");

        client.clearCaches();

        server.verify();
    }

    @Test
    void clearCaches_権限不足はKeycloakAdminExceptionにする() {
        expectToken();
        server.expect(requestTo(ADMIN_BASE_URI + "/clear-user-cache"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        KeycloakAdminException ex = assertThrows(KeycloakAdminException.class, () -> client.clearCaches());

        assertTrue(ex.getMessage().contains("キャッシュ"));
    }

    @Test
    void clearCaches_Keycloakに到達できない場合もKeycloakAdminExceptionにする() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThrows(KeycloakAdminException.class, () -> client.clearCaches());
    }

    @Test
    void clearCaches_途中の失敗で残りを呼ばずに例外にする() {
        expectToken();
        expectClear("/clear-user-cache");
        server.expect(requestTo(ADMIN_BASE_URI + "/clear-realm-cache"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThrows(KeycloakAdminException.class, () -> client.clearCaches());
    }
}
