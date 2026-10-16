package com.letsblog.identity.client;

import com.letsblog.common.auth.ServiceTokenClient;
import com.letsblog.identity.service.ProjectNotFoundException;
import com.letsblog.identity.testsupport.FakeHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ProjectServiceClient}の失敗系。HTTPはサーバー側フェイク({@link FakeHttpServer})で、クライアントは実物。 */
class ProjectServiceClientTest {

    private static final String TOKEN_PATH = "/realms/letsblog/protocol/openid-connect/token";

    private FakeHttpServer server;
    private ProjectServiceClient client;

    @BeforeEach
    void setUp() {
        server = new FakeHttpServer();
        server.json("POST", TOKEN_PATH, "{\"access_token\":\"svc-token\",\"expires_in\":300}");
        client = clientFor(server.baseUrl());
    }

    private ProjectServiceClient clientFor(String baseUrl) {
        ServiceTokenClient tokenClient = new ServiceTokenClient(
                RestClient.builder(), server.baseUrl() + TOKEN_PATH, "letsblog-services", "test-secret");
        OutboundAuthHeaders auth = new OutboundAuthHeaders(new MockHttpServletRequest(), tokenClient);
        return new ProjectServiceClient(RestClient.builder(), baseUrl, auth);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void getProject_登録が無ければProjectNotFound() {
        server.status("GET", "/api/internal/project/projects/1", 404, "{}");

        assertThrows(ProjectNotFoundException.class, () -> client.getProject(1L));
    }

    @Test
    void getProject_404以外のエラーは応答本文を添えたIllegalStateException() {
        server.status("GET", "/api/internal/project/projects/1", 500, "boom-body");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> client.getProject(1L));

        assertTrue(e.getMessage().contains("プロジェクト照会"), e.getMessage());
        assertTrue(e.getMessage().contains("boom-body"), e.getMessage());
    }

    @Test
    void getProject_本文が空のエラーは例外のメッセージで代える() {
        server.on("GET", "/api/internal/project/projects/1", FakeHttpServer.Response.empty(503));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> client.getProject(1L));

        assertTrue(e.getMessage().contains("503"), e.getMessage());
    }

    @Test
    void getProject_成功でも本文が空ならIllegalStateException() {
        server.on("GET", "/api/internal/project/projects/1", FakeHttpServer.Response.empty(200));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> client.getProject(1L));

        assertTrue(e.getMessage().contains("空の応答"), e.getMessage());
    }

    @Test
    void getProject_接続できなければIllegalStateException() {
        FakeHttpServer gone = new FakeHttpServer();
        String closedUrl = gone.baseUrl();
        gone.close();
        ProjectServiceClient unreachable = clientFor(closedUrl);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> unreachable.getProject(1L));

        assertTrue(e.getMessage().contains("プロジェクト照会"), e.getMessage());
    }

    @Test
    void getSite_接続できなければIllegalStateException() {
        FakeHttpServer gone = new FakeHttpServer();
        String closedUrl = gone.baseUrl();
        gone.close();
        ProjectServiceClient unreachable = clientFor(closedUrl);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> unreachable.getSite(2L));

        assertTrue(e.getMessage().contains("サイト照会"), e.getMessage());
    }

    @Test
    void getSite_404以外のエラーは応答本文を添えたIllegalStateException() {
        server.status("GET", "/api/internal/project/sites/2", 500, "site-boom");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> client.getSite(2L));

        assertTrue(e.getMessage().contains("サイト照会"), e.getMessage());
        assertTrue(e.getMessage().contains("site-boom"), e.getMessage());
    }

    @Test
    void getSite_本文が空の成功応答はempty() {
        server.on("GET", "/api/internal/project/sites/2", FakeHttpServer.Response.empty(200));

        assertTrue(client.getSite(2L).isEmpty());
    }

    @Test
    void getSite_登録が無ければempty() {
        server.status("GET", "/api/internal/project/sites/2", 404, "{}");

        assertEquals(java.util.Optional.empty(), client.getSite(2L));
    }
}
