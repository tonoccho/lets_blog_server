package com.letsblog.platform.keycloak;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    // ---- setPassword / findUserIdByEmail(issue #693。issue #1825で分岐を網羅) ----

    private static final String USERS_QUERY = ADMIN_BASE_URI + "/users?email=a@example.com&exact=true";

    private void expectLookup(org.springframework.test.web.client.ResponseCreator response) {
        expectToken();
        server.expect(requestTo(USERS_QUERY)).andExpect(header("Authorization", "Bearer tok")).andRespond(response);
    }

    private void expectReset(org.springframework.test.web.client.ResponseCreator response) {
        expectToken();
        server.expect(requestTo(ADMIN_BASE_URI + "/users/kc-1/reset-password"))
                .andExpect(method(org.springframework.http.HttpMethod.PUT))
                .andExpect(header("Authorization", "Bearer tok"))
                .andExpect(content().string(containsString("\"temporary\":false")))
                .andRespond(response);
    }

    @Test
    void setPassword_即時設定のリクエストを送る() {
        expectReset(withStatus(HttpStatus.NO_CONTENT));

        client.setPassword("kc-1", "new-password-123");

        server.verify();
    }

    @Test
    void setPassword_認可エラーは権限の確認を促すメッセージにする() {
        expectReset(withStatus(HttpStatus.FORBIDDEN));

        KeycloakAdminException ex = assertThrows(KeycloakAdminException.class, () -> client.setPassword("kc-1", "x"));

        assertTrue(ex.getMessage().contains("manage-users"), ex.getMessage());
    }

    @Test
    void setPassword_401も同じ認可エラーのメッセージにする() {
        expectReset(withStatus(HttpStatus.UNAUTHORIZED));

        KeycloakAdminException ex = assertThrows(KeycloakAdminException.class, () -> client.setPassword("kc-1", "x"));

        assertTrue(ex.getMessage().contains("manage-users"), ex.getMessage());
    }

    @Test
    void setPassword_409は重複のメッセージにする() {
        expectReset(withStatus(HttpStatus.CONFLICT));

        KeycloakAdminException ex = assertThrows(KeycloakAdminException.class, () -> client.setPassword("kc-1", "x"));

        assertTrue(ex.getMessage().contains("既に存在"), ex.getMessage());
    }

    @Test
    void setPassword_その他のエラーはステータスと本文を含める() {
        expectReset(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("kaboom"));

        KeycloakAdminException ex = assertThrows(KeycloakAdminException.class, () -> client.setPassword("kc-1", "x"));

        assertTrue(ex.getMessage().contains("500") && ex.getMessage().contains("kaboom"), ex.getMessage());
    }

    @Test
    void setPassword_Keycloakに到達できなければ停止の可能性を伝える() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        KeycloakAdminException ex = assertThrows(KeycloakAdminException.class, () -> client.setPassword("kc-1", "x"));

        assertTrue(ex.getMessage().contains("停止している可能性"), ex.getMessage());
    }

    @Test
    void findUserIdByEmail_最初の一致のidを返す() {
        expectLookup(withSuccess("[{\"id\":\"kc-1\"},{\"id\":\"kc-2\"}]", MediaType.APPLICATION_JSON));

        assertEquals(java.util.Optional.of("kc-1"), client.findUserIdByEmail("a@example.com"));
    }

    @Test
    void findUserIdByEmail_空の配列は該当なし() {
        expectLookup(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertEquals(java.util.Optional.empty(), client.findUserIdByEmail("a@example.com"));
    }

    @Test
    void findUserIdByEmail_配列でない応答は該当なし() {
        expectLookup(withSuccess("{\"id\":\"kc-1\"}", MediaType.APPLICATION_JSON));

        assertEquals(java.util.Optional.empty(), client.findUserIdByEmail("a@example.com"));
    }

    @Test
    void findUserIdByEmail_本文が空なら該当なし() {
        expectLookup(withSuccess());

        assertEquals(java.util.Optional.empty(), client.findUserIdByEmail("a@example.com"));
    }

    @Test
    void findUserIdByEmail_idが無い要素は該当なし() {
        expectLookup(withSuccess("[{\"username\":\"a\"}]", MediaType.APPLICATION_JSON));

        assertEquals(java.util.Optional.empty(), client.findUserIdByEmail("a@example.com"));
    }

    @Test
    void findUserIdByEmail_404は該当なし() {
        expectLookup(withStatus(HttpStatus.NOT_FOUND));

        assertEquals(java.util.Optional.empty(), client.findUserIdByEmail("a@example.com"));
    }

    @Test
    void findUserIdByEmail_404以外のエラーは例外にする() {
        expectLookup(withStatus(HttpStatus.FORBIDDEN));

        KeycloakAdminException ex =
                assertThrows(KeycloakAdminException.class, () -> client.findUserIdByEmail("a@example.com"));

        assertTrue(ex.getMessage().contains("manage-users"), ex.getMessage());
    }

    @Test
    void findUserIdByEmail_Keycloakに到達できなければ例外にする() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThrows(KeycloakAdminException.class, () -> client.findUserIdByEmail("a@example.com"));
    }
}
