package com.letsblog.content.client;

import static org.assertj.core.api.Assertions.assertThat;

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
class ProjectBridgeClientBridgeDateTimeTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void resolveProjectSlug_はZ付きの日時を含む応答を読める() throws IOException {
        server = start("{\"id\":7,\"name\":\"P\",\"slug\":\"p\",\"masterEnvironment\":\"test\",\"localSiteId\":1,\"testSiteId\":2,\"productionSiteId\":3,\"githubRepository\":null,\"createdAt\":\"2026-09-08T20:03:35Z\",\"updatedAt\":\"2026-09-09T01:02:03Z\"}");
        assertThat(new ProjectBridgeClient(RestClient.builder(), baseUrl()).resolveProjectSlug(7L, "t")).isEqualTo("p");
    }

    @Test
    void resolveSiteIdByKey_はZ付きの日時を含む応答を読める() throws IOException {
        server = start("{\"id\":10,\"siteKey\":\"my-blog\",\"name\":\"My Blog\",\"baseUrl\":\"https://example.com\",\"cmsType\":\"WORDPRESS\",\"managedWordpress\":false,\"wpSlug\":null,\"createdAt\":\"2026-09-08T20:03:35Z\",\"updatedAt\":\"2026-09-09T01:02:03Z\"}");
        assertThat(new ProjectBridgeClient(RestClient.builder(), baseUrl()).resolveSiteIdByKey("my-blog", "t")).isEqualTo(10L);
    }

    @Test
    void listSites_はZ付きの日時を含む応答を読める() throws IOException {
        server = start("[{\"id\":10,\"siteKey\":\"my-blog\",\"name\":\"My Blog\",\"baseUrl\":\"https://example.com\",\"cmsType\":\"WORDPRESS\",\"managedWordpress\":false,\"wpSlug\":null,\"createdAt\":\"2026-09-08T20:03:35Z\",\"updatedAt\":\"2026-09-09T01:02:03Z\"}]");
        assertThat(new ProjectBridgeClient(RestClient.builder(), baseUrl()).listSites("t")).hasSize(1);
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
