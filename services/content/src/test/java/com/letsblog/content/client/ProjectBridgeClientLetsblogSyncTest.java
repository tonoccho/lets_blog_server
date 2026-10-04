package com.letsblog.content.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.letsblog.content.service.IdentityServiceUnavailableException;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** タグ等の変更を project-service へ伝える呼び出し(issue #1558)。 */
class ProjectBridgeClientLetsblogSyncTest {

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private ProjectBridgeClient start(int status) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            path.set(exchange.getRequestURI().getPath() + " " + exchange.getRequestMethod());
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        return new ProjectBridgeClient(RestClient.builder(), "http://localhost:" + server.getAddress().getPort());
    }

    @Test
    void プロジェクトIDと呼び出し元のトークンをPOSTする() throws IOException {
        start(202).requestLetsblogSync(7L, "Bearer t");

        assertThat(path.get()).isEqualTo("/api/internal/project/letsblog-sync POST");
        assertThat(body.get()).contains("\"projectId\":7");
        assertThat(auth.get()).isEqualTo("Bearer t");
    }

    @Test
    void グローバルならプロジェクトIDはnull() throws IOException {
        start(202).requestLetsblogSync(null, "Bearer t");

        assertThat(body.get()).contains("\"projectId\":null");
    }

    @Test
    void トークンが無ければAuthorizationを付けない() throws IOException {
        start(202).requestLetsblogSync(7L, null);

        assertThat(auth.get()).isNull();
    }

    @Test
    void 失敗したら例外() throws IOException {
        ProjectBridgeClient client = start(500);

        assertThatThrownBy(() -> client.requestLetsblogSync(7L, "Bearer t"))
                .isInstanceOf(IdentityServiceUnavailableException.class);
    }
}
