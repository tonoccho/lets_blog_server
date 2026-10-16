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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProjectBridgeClient#resolveGithubAccess(issue #1824)の、認可ヘッダー・空の応答・本文の無いHTTPエラー・
 * project-serviceへ接続できない場合。409のdetails.reasonの扱いは{@link ProjectBridgeClientGithubAccessTest}が担当する。
 */
class ProjectBridgeClientTest {

    private static final String PATH = "/api/internal/project/projects/1/github-access";

    private FakeHttpServer server;
    private ProjectBridgeClient client;

    @BeforeEach
    void setUp() {
        server = new FakeHttpServer();
        client = new ProjectBridgeClient(RestClient.builder(), server.baseUrl());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void アクセス情報を返しBearerトークンと操作者を転送する() {
        server.json("GET", PATH, "{\"token\":\"t\",\"owner\":\"o\",\"repo\":\"r\"}");

        ProjectBridgeClient.GithubAccess access = client.resolveGithubAccess(1L, 2L, "Bearer x");

        assertEquals(new ProjectBridgeClient.GithubAccess("t", "o", "r"), access);
        assertEquals("Bearer x", server.last().header("Authorization"));
        assertEquals("actorUserId=2", server.last().query());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void トークンがnullや空白ならAuthorizationヘッダーを付けない(String token) {
        server.json("GET", PATH, "{\"token\":\"t\",\"owner\":\"o\",\"repo\":\"r\"}");

        client.resolveGithubAccess(1L, 2L, token);

        assertNull(server.last().header("Authorization"));
    }

    @Test
    void 応答が空なら例外にする() {
        server.on("GET", PATH, FakeHttpServer.Response.empty(200));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> client.resolveGithubAccess(1L, 2L, "Bearer x"));
        assertTrue(e.getMessage().contains("空の応答"), e.getMessage());
    }

    @Test
    void HTTPエラーで本文が空ならステータスのメッセージを使う() {
        server.on("GET", PATH, FakeHttpServer.Response.empty(503));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> client.resolveGithubAccess(1L, 2L, "Bearer x"));
        assertTrue(e.getMessage().contains("503"), e.getMessage());
    }

    @Test
    void project_serviceへ接続できなければ専用の例外にする() {
        server.close();

        IdentityServiceUnavailableException e = assertThrows(
                IdentityServiceUnavailableException.class, () -> client.resolveGithubAccess(1L, 2L, "Bearer x"));
        assertTrue(e.getMessage().contains("github-access"), e.getMessage());
    }
}
