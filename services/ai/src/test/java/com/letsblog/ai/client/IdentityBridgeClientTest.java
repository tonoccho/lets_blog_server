package com.letsblog.ai.client;

import com.letsblog.ai.service.IdentityServiceUnavailableException;
import com.letsblog.ai.testsupport.FakeHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** IdentityBridgeClient#isProjectMember(issue #1824): 認可ヘッダーの付与・判定結果・identity-service障害を実HTTPで通す。 */
class IdentityBridgeClientTest {

    private static final String PATH = "/api/internal/identity/projects/7/members/10";

    private FakeHttpServer server;
    private IdentityBridgeClient client;

    @BeforeEach
    void setUp() {
        server = new FakeHttpServer();
        client = new IdentityBridgeClient(RestClient.builder(), server.baseUrl());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void メンバーならtrueでBearerトークンを転送する() {
        server.json("GET", PATH, "true");

        assertTrue(client.isProjectMember(7L, 10L, "Bearer t"));
        assertEquals("Bearer t", server.last().header("Authorization"));
    }

    @Test
    void メンバーでなければfalse() {
        server.json("GET", PATH, "false");

        assertFalse(client.isProjectMember(7L, 10L, "Bearer t"));
    }

    @Test
    void 応答が空ならメンバーとは見なさない() {
        server.on("GET", PATH, FakeHttpServer.Response.empty(200));

        assertFalse(client.isProjectMember(7L, 10L, "Bearer t"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void トークンがnullや空白ならAuthorizationヘッダーを付けない(String token) {
        server.json("GET", PATH, "true");

        assertTrue(client.isProjectMember(7L, 10L, token));
        assertNull(server.last().header("Authorization"));
    }

    @Test
    void identity_serviceの障害は専用の例外にする() {
        server.status("GET", PATH, 500, "boom");

        IdentityServiceUnavailableException e = assertThrows(
                IdentityServiceUnavailableException.class, () -> client.isProjectMember(7L, 10L, "Bearer t"));
        assertTrue(e.getMessage().contains("プロジェクトメンバー判定"), e.getMessage());
    }
}
