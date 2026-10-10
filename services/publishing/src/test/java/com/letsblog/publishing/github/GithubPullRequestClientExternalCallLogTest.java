package com.letsblog.publishing.github;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.letsblog.common.client.ExternalCallLoggingInterceptor;
import com.letsblog.publishing.client.ProjectServiceClient.GithubAccess;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/** GithubPullRequestClientの外部呼び出しが1呼び出し1行で記録され、トークンは出ない(issue #1734)。 */
class GithubPullRequestClientExternalCallLogTest {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(ExternalCallLoggingInterceptor.class);
    private HttpServer server;

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void PR一覧の取得はtarget_githubの行を1行出しクエリとトークンは出さない() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        GithubPullRequestClient client = new GithubPullRequestClient(
                RestClient.builder(), "http://localhost:" + server.getAddress().getPort());

        client.listOpenPullRequests(new GithubAccess("tok-secret", "octo", "blog"));

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage())
                .startsWith("external call: target=github host=localhost method=GET path=/repos/octo/blog/pulls "
                        + "status=200 duration_ms=")
                .endsWith("outcome=success")
                .doesNotContain("tok-secret", "per_page", "state=open");
    }
}
