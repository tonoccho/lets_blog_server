package com.letsblog.media.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.letsblog.media.service.ProjectNotFoundException;
import com.letsblog.media.testsupport.FakeHttpServer;
import com.letsblog.media.testsupport.FakeHttpServer.Response;
import com.letsblog.media.testsupport.ServiceTokenFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;

/** #1820: {@link ProjectServiceClient#requireProjectExists}の分岐を、HTTPのサーバー側フェイクに対して実物で通す。 */
class ProjectServiceClientTest {

    private FakeHttpServer server;

    @BeforeEach
    void setUp() {
        server = new FakeHttpServer();
        server.json("POST", ServiceTokenFixtures.TOKEN_PATH,
                "{\"access_token\":\"" + ServiceTokenFixtures.TOKEN + "\",\"expires_in\":3600}");
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private ProjectServiceClient client(String baseUrl) {
        OutboundAuthHeaders auth =
                new OutboundAuthHeaders(new MockHttpServletRequest(), ServiceTokenFixtures.client(server.baseUrl()));
        return new ProjectServiceClient(RestClient.builder(), baseUrl, auth);
    }

    @Test
    void projectIdがnullなら何も呼ばない() {
        assertThatCode(() -> client(server.baseUrl()).requireProjectExists(null)).doesNotThrowAnyException();

        assertThat(server.requests("GET", ".*")).isEmpty();
    }

    @Test
    void 実在するプロジェクトは例外にならない() {
        server.json("GET", "/api/internal/project/projects/7", "{\"id\":7,\"name\":\"P\",\"slug\":\"p\"}");

        assertThatCode(() -> client(server.baseUrl()).requireProjectExists(7L)).doesNotThrowAnyException();
    }

    @Test
    void 実在しないプロジェクトはProjectNotFoundExceptionになる() {
        server.status("GET", "/api/internal/project/projects/9", 404, "{\"error\":\"x\"}");

        assertThatThrownBy(() -> client(server.baseUrl()).requireProjectExists(9L))
                .isInstanceOf(ProjectNotFoundException.class)
                .hasMessageContaining("id 9");
    }

    @Test
    void 応答の本文があるエラーは本文を添えたIllegalStateExceptionになる() {
        server.status("GET", "/api/internal/project/projects/5", 500, "{\"error\":\"boom\"}");

        assertThatThrownBy(() -> client(server.baseUrl()).requireProjectExists(5L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("project-serviceのプロジェクト照会呼び出しに失敗しました")
                .hasMessageContaining("boom");
    }

    @Test
    void 応答の本文が空のエラーは例外のメッセージを添えたIllegalStateExceptionになる() {
        server.on("GET", "/api/internal/project/projects/6", Response.empty(503));

        assertThatThrownBy(() -> client(server.baseUrl()).requireProjectExists(6L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("503");
    }

    @Test
    void 接続できないときはIllegalStateExceptionになる() {
        assertThatThrownBy(() -> client("http://127.0.0.1:1").requireProjectExists(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("project-serviceのプロジェクト照会呼び出しに失敗しました");
    }
}
