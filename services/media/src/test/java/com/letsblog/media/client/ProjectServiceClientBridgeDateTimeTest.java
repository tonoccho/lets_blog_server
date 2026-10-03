package com.letsblog.media.client;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * issue #1541: project-service の内部ブリッジ応答の日時が Z 終端 RFC 3339 になっても、
 * 本クライアントの DTO({@code createdAt}/{@code updatedAt} を持たない)が例外なく読めること。
 */
class ProjectServiceClientBridgeDateTimeTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void requireProjectExists_はZ付きの日時を含む応答を読める() throws IOException {
        server = start("{\"id\":7,\"name\":\"P\",\"slug\":\"p\",\"masterEnvironment\":\"test\",\"localSiteId\":1,\"testSiteId\":2,\"productionSiteId\":3,\"githubRepository\":null,\"createdAt\":\"2026-09-08T20:03:35Z\",\"updatedAt\":\"2026-09-09T01:02:03Z\"}");
        OutboundAuthHeaders auth = mock(OutboundAuthHeaders.class);
        when(auth.current()).thenReturn(headers -> { });
        ProjectServiceClient client = new ProjectServiceClient(RestClient.builder(), baseUrl(), auth);

        assertThatCode(() -> client.requireProjectExists(7L)).doesNotThrowAnyException();
    }

    private HttpServer start(String body) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        s.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        s.start();
        return s;
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }
}
