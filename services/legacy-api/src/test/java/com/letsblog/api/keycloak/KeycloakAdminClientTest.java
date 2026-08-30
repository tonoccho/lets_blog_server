package com.letsblog.api.keycloak;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * legacy-api版KeycloakAdminClientの回帰テスト(#681)。
 * services/identity/.../keycloak/KeycloakAdminClientTestと同じ流儀でMockRestServiceServerを使う。
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
                .andExpect(content().string(containsString("\"email\":\"admin@example.com\"")))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .location(java.net.URI.create(ADMIN_BASE_URI + "/users/admin-sub-1")));

        String sub = client.createUser("admin@example.com");

        assertEquals("admin-sub-1", sub);
        server.verify();
    }

    @Test
    void createUser_既に存在する場合は例外になる() {
        expectTokenRequest();
        server.expect(requestTo(ADMIN_BASE_URI + "/users"))
                .andRespond(withStatus(HttpStatus.CONFLICT));

        KeycloakAdminException exception = assertThrows(KeycloakAdminException.class,
                () -> client.createUser("dup@example.com"));

        org.hamcrest.MatcherAssert.assertThat(exception.getMessage(), containsString("既に存在"));
    }

    @Test
    void createUser_Keycloakが停止していれば例外になる() {
        expectTokenRequest();
        server.expect(requestTo(ADMIN_BASE_URI + "/users"))
                .andRespond(request -> {
                    throw new java.io.IOException("Connection refused");
                });

        assertThrows(KeycloakAdminException.class, () -> client.createUser("down@example.com"));
    }

    @Test
    void setPassword_type_value_temporary_falseで即時設定する() {
        expectTokenRequest();
        server.expect(requestTo(ADMIN_BASE_URI + "/users/sub-1/reset-password"))
                .andExpect(method(PUT))
                .andExpect(header("Authorization", "Bearer test-access-token"))
                .andExpect(content().string("{\"type\":\"password\",\"value\":\"NewPassw0rd!\",\"temporary\":false}"))
                .andRespond(withSuccess());

        client.setPassword("sub-1", "NewPassw0rd!");

        server.verify();
    }

    @Test
    void setPassword_対象ユーザーが存在しない場合は例外になる() {
        expectTokenRequest();
        server.expect(requestTo(ADMIN_BASE_URI + "/users/missing-sub/reset-password"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThrows(KeycloakAdminException.class, () -> client.setPassword("missing-sub", "whatever123"));
    }

    @Test
    void findUserIdByEmail_該当ユーザーがいればidを返す() {
        expectTokenRequest();
        server.expect(requestToUriTemplate(ADMIN_BASE_URI + "/users?email={email}&exact={exact}",
                        "admin@example.com", "true"))
                .andExpect(method(GET))
                .andRespond(withSuccess(
                        "[{\"id\":\"found-sub\",\"email\":\"admin@example.com\"}]", MediaType.APPLICATION_JSON));

        Optional<String> result = client.findUserIdByEmail("admin@example.com");

        assertEquals(Optional.of("found-sub"), result);
        server.verify();
    }

    @Test
    void findUserIdByEmail_該当なしならempty() {
        expectTokenRequest();
        server.expect(requestToUriTemplate(ADMIN_BASE_URI + "/users?email={email}&exact={exact}",
                        "missing@example.com", "true"))
                .andExpect(method(GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertFalse(client.findUserIdByEmail("missing@example.com").isPresent());
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
    void トークンは有効期限内なら使い回す() {
        expectTokenRequest();
        server.expect(requestTo(ADMIN_BASE_URI + "/users/sub-a"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(ADMIN_BASE_URI + "/users/sub-b"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        client.deleteUser("sub-a");
        client.deleteUser("sub-b");

        // トークンエンドポイントへのリクエストは1回だけ積んでいるため、
        // 2回目の呼び出しでトークンを再取得していれば server.verify() が失敗する。
        server.verify();
    }
}
