package com.letsblog.analytics.client;

import com.letsblog.analytics.service.IdentityServiceUnavailableException;
import com.letsblog.analytics.testsupport.FakeHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IdentityBridgeClientの回帰テスト(issue #1827)。identity-serviceをサーバー側フェイクのHTTPで差し替え、
 * 実物のクライアントを通す。
 */
class IdentityBridgeClientTest {

    private static final String MEMBER_PATH = "/api/internal/identity/projects/1/members/10";

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
    void メンバーならtrueを返し呼び出し元のトークンを転送する() {
        server.json("GET", MEMBER_PATH, "true");

        assertTrue(client.isProjectMember(1L, 10L, "Bearer t"));
        assertEquals("Bearer t", server.last().header("Authorization"));
    }

    @Test
    void メンバーでなければfalse() {
        server.json("GET", MEMBER_PATH, "false");

        assertFalse(client.isProjectMember(1L, 10L, "Bearer t"));
    }

    @Test
    void 本文が空ならfalse() {
        server.on("GET", MEMBER_PATH, FakeHttpServer.Response.empty(200));

        assertFalse(client.isProjectMember(1L, 10L, "Bearer t"));
    }

    @Test
    void トークンがnullや空白ならAuthorizationを付けない() {
        server.json("GET", MEMBER_PATH, "true");

        assertTrue(client.isProjectMember(1L, 10L, null));
        assertNull(server.last().header("Authorization"));
        assertTrue(client.isProjectMember(1L, 10L, "  "));
        assertNull(server.last().header("Authorization"));
    }

    @Test
    void identity_serviceが失敗を返したらIdentityServiceUnavailableException() {
        server.status("GET", MEMBER_PATH, 500, "{}");

        IdentityServiceUnavailableException e = assertThrows(
                IdentityServiceUnavailableException.class, () -> client.isProjectMember(1L, 10L, "Bearer t"));
        assertTrue(e.getMessage().contains("プロジェクトメンバー判定呼び出しに失敗しました"));
    }

    @Test
    void identity_serviceへ届かなければIdentityServiceUnavailableException() {
        server.close();

        assertThrows(IdentityServiceUnavailableException.class, () -> client.isProjectMember(1L, 10L, "Bearer t"));
    }
}
