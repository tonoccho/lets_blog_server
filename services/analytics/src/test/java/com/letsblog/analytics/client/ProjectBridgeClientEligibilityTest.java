package com.letsblog.analytics.client;

import com.letsblog.analytics.service.IdentityServiceUnavailableException;
import com.letsblog.analytics.service.ProjectNotFoundException;
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
 * ProjectBridgeClient#getProjectEligibility の回帰テスト(issue #1827)。project-serviceをサーバー側フェイクの
 * HTTPで差し替え、実物のクライアントを通す。
 */
class ProjectBridgeClientEligibilityTest {

    private static final String PATH = "/api/internal/project/projects/5/eligibility";

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
    void 本番サイトの有無を返し呼び出し元のトークンを転送する() {
        server.json("GET", PATH, "{\"hasProductionSite\":true}");

        assertTrue(client.getProjectEligibility(5L, "Bearer t").hasProductionSite());
        assertEquals("Bearer t", server.last().header("Authorization"));
    }

    @Test
    void 本番サイトが無ければfalse() {
        server.json("GET", PATH, "{\"hasProductionSite\":false}");

        assertFalse(client.getProjectEligibility(5L, "Bearer t").hasProductionSite());
    }

    @Test
    void トークンがnullや空白ならAuthorizationを付けない() {
        server.json("GET", PATH, "{\"hasProductionSite\":true}");

        client.getProjectEligibility(5L, null);
        assertNull(server.last().header("Authorization"));
        client.getProjectEligibility(5L, " ");
        assertNull(server.last().header("Authorization"));
    }

    @Test
    void project_serviceが404ならProjectNotFoundException() {
        server.status("GET", PATH, 404, "{}");

        ProjectNotFoundException e =
                assertThrows(ProjectNotFoundException.class, () -> client.getProjectEligibility(5L, "Bearer t"));
        assertEquals("id 5 のプロジェクトは登録されていません", e.getMessage());
    }

    @Test
    void project_serviceが404以外の失敗を返したらIdentityServiceUnavailableException() {
        server.status("GET", PATH, 500, "{}");

        IdentityServiceUnavailableException e = assertThrows(
                IdentityServiceUnavailableException.class, () -> client.getProjectEligibility(5L, "Bearer t"));
        assertTrue(e.getMessage().contains("project-serviceのプロジェクト情報取得に失敗しました"));
    }

    @Test
    void 応答の本文が空ならIdentityServiceUnavailableException() {
        server.on("GET", PATH, FakeHttpServer.Response.empty(200));

        IdentityServiceUnavailableException e = assertThrows(
                IdentityServiceUnavailableException.class, () -> client.getProjectEligibility(5L, "Bearer t"));
        assertTrue(e.getMessage().contains("空の応答"));
    }

    @Test
    void project_serviceへ届かなければIdentityServiceUnavailableException() {
        server.close();

        assertThrows(IdentityServiceUnavailableException.class, () -> client.getProjectEligibility(5L, "Bearer t"));
    }
}
