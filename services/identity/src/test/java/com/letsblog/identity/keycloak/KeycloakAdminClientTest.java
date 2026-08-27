package com.letsblog.identity.keycloak;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * KeycloakAdminClientの回帰テスト。GithubClientTestと同様、MockRestServiceServerでHTTP通信を検証する。
 * トークンエンドポイントと管理APIエンドポイントの2つのRestClientを内部で持つため、
 * 呼び出し順(トークン取得→本体の呼び出し)ごとexpectを積む。
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

    private void expectTokenRequest() {
        server.expect(requestTo(TOKEN_URI))
                .andExpect(method(POST))
                .andExpect(content().string(containsString("client_id=letsblog-services")))
                .andExpect(content().string(containsString("grant_type=client_credentials")))
                .andRespond(withSuccess(
                        "{\"access_token\":\"test-access-token\",\"expires_in\":60}",
                        MediaType.APPLICATION_JSON));
    }

    @Test
    void createUser_成功時にLocationヘッダーからsubを取り出す() {
        expectTokenRequest();
        server.expect(requestTo(ADMIN_BASE_URI + "/users"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer test-access-token"))
                .andExpect(content().string(containsString("\"email\":\"user@example.com\"")))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .location(java.net.URI.create(ADMIN_BASE_URI + "/users/abc-123-sub")));

        String sub = client.createUser("user@example.com", "太郎", "山田", false);

        assertEquals("abc-123-sub", sub);
        server.verify();
    }

    @Test
    void createUser_requiredActionsを含める() {
        expectTokenRequest();
        server.expect(requestTo(ADMIN_BASE_URI + "/users"))
                .andExpect(method(POST))
                .andExpect(content().string(containsString("\"requiredActions\":[\"UPDATE_PASSWORD\"]")))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .location(java.net.URI.create(ADMIN_BASE_URI + "/users/migrated-sub")));

        String sub = client.createUser("legacy@example.com", null, null, true);

        assertEquals("migrated-sub", sub);
        server.verify();
    }

    @Test
    void createUser_Keycloakが停止していれば例外になる() {
        expectTokenRequest();
        server.expect(requestTo(ADMIN_BASE_URI + "/users"))
                .andRespond(request -> {
                    throw new java.io.IOException("Connection refused");
                });

        KeycloakUserSyncException exception = assertThrows(KeycloakUserSyncException.class,
                () -> client.createUser("down@example.com", null, null, false));

        org.hamcrest.MatcherAssert.assertThat(exception.getMessage(), containsString("Keycloak"));
    }

    @Test
    void createUser_サーバーエラー応答でも例外になる() {
        expectTokenRequest();
        server.expect(requestTo(ADMIN_BASE_URI + "/users"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThrows(KeycloakUserSyncException.class,
                () -> client.createUser("down@example.com", null, null, false));
    }

    @Test
    void setEnabled_falseで無効化リクエストを送る() {
        expectTokenRequest();
        server.expect(requestTo(ADMIN_BASE_URI + "/users/sub-1"))
                .andExpect(method(PUT))
                .andExpect(content().string("{\"enabled\":false}"))
                .andRespond(withSuccess());

        client.setEnabled("sub-1", false);

        server.verify();
    }

    @Test
    void deleteUser_DELETEリクエストを送る() {
        expectTokenRequest();
        server.expect(requestTo(ADMIN_BASE_URI + "/users/sub-2"))
                .andExpect(method(DELETE))
                .andRespond(withSuccess());

        client.deleteUser("sub-2");

        server.verify();
    }

    @Test
    void deleteUser_404は冪等に成功扱いにする() {
        expectTokenRequest();
        server.expect(requestTo(ADMIN_BASE_URI + "/users/already-gone-sub"))
                .andExpect(method(DELETE))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        client.deleteUser("already-gone-sub");

        server.verify();
    }

    @Test
    void sendPasswordResetEmail_execute_actions_emailを呼ぶ() {
        expectTokenRequest();
        server.expect(requestTo(ADMIN_BASE_URI + "/users/sub-3/execute-actions-email"))
                .andExpect(method(PUT))
                .andExpect(content().string("[\"UPDATE_PASSWORD\"]"))
                .andRespond(withSuccess());

        client.sendPasswordResetEmail("sub-3");

        server.verify();
    }

    @Test
    void setPassword_type_value_temporary_falseで即時設定する() {
        expectTokenRequest();
        server.expect(requestTo(ADMIN_BASE_URI + "/users/sub-4/reset-password"))
                .andExpect(method(PUT))
                .andExpect(content().string("{\"type\":\"password\",\"value\":\"NewPassw0rd!\",\"temporary\":false}"))
                .andRespond(withSuccess());

        client.setPassword("sub-4", "NewPassw0rd!");

        server.verify();
    }

    @Test
    void setPassword_失敗レスポンスは例外になる() {
        expectTokenRequest();
        server.expect(requestTo(ADMIN_BASE_URI + "/users/sub-5/reset-password"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThrows(KeycloakUserSyncException.class, () -> client.setPassword("sub-5", "bad"));
    }

    @Test
    void setPassword_対象ユーザーが存在しない場合は例外になる() {
        expectTokenRequest();
        server.expect(requestTo(ADMIN_BASE_URI + "/users/missing-sub/reset-password"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThrows(KeycloakUserSyncException.class, () -> client.setPassword("missing-sub", "whatever123"));
    }

    @Test
    void exists_404なら存在しないとみなしfalse() {
        expectTokenRequest();
        server.expect(requestTo(ADMIN_BASE_URI + "/users/missing-sub"))
                .andExpect(method(GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertFalse(client.exists("missing-sub"));
    }

    @Test
    void exists_200なら存在するとみなしtrue() {
        expectTokenRequest();
        server.expect(requestTo(ADMIN_BASE_URI + "/users/present-sub"))
                .andExpect(method(GET))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertTrue(client.exists("present-sub"));
    }

    @Test
    void exists_404以外のエラーは例外になる() {
        expectTokenRequest();
        server.expect(requestTo(ADMIN_BASE_URI + "/users/forbidden-sub"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThrows(KeycloakUserSyncException.class, () -> client.exists("forbidden-sub"));
    }

    @Test
    void トークンは有効期限内なら使い回す() {
        expectTokenRequest();
        server.expect(requestTo(ADMIN_BASE_URI + "/users/sub-a"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(ADMIN_BASE_URI + "/users/sub-b"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        client.exists("sub-a");
        client.exists("sub-b");

        // トークンエンドポイントへのリクエストは1回だけ積んでいるため、
        // 2回目のexists呼び出しでトークンを再取得していれば server.verify() が失敗する。
        server.verify();
    }
}
